// Lamsa AI proxy — Cloudflare Worker (free tier: 100k requests/day)
// نفس منطق خادم Vercel: تحقق من هوية Firebase + اشتراك VIP، ثم تمرير الطلب إلى Groq.
const MAX_TOKENS = 1500;
let jwks = null, jwksExp = 0;
const hits = new Map();

const b64uBytes = s => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)), c => c.charCodeAt(0));
const b64uJson = s => JSON.parse(new TextDecoder().decode(b64uBytes(s)));

async function googleKeys() {
  if (jwks && Date.now() < jwksExp) return jwks;
  const r = await fetch('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com');
  jwks = (await r.json()).keys || [];
  const m = /max-age=(\d+)/.exec(r.headers.get('cache-control') || '');
  jwksExp = Date.now() + (m ? Number(m[1]) * 1000 : 3600e3);
  return jwks;
}

async function verifyIdToken(tok, projectId) {
  const [h, p, sig] = String(tok || '').split('.');
  if (!sig) throw new Error('bad token');
  const header = b64uJson(h), payload = b64uJson(p);
  const jwk = (await googleKeys()).find(k => k.kid === header.kid);
  if (header.alg !== 'RS256' || !jwk) throw new Error('bad key');
  const key = await crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, b64uBytes(sig), new TextEncoder().encode(`${h}.${p}`));
  const now = Date.now() / 1000;
  if (!ok || payload.aud !== projectId || payload.iss !== `https://securetoken.google.com/${projectId}`
      || payload.exp < now || payload.iat > now + 300 || !payload.sub) throw new Error('invalid token');
  return payload.sub;
}

async function vipStatus(uid, idToken, projectId) {
  const r = await fetch(`https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/users/${uid}`,
    { headers: { Authorization: 'Bearer ' + idToken } });
  if (!r.ok) return false;
  const f = (await r.json()).fields || {};
  const exp = Date.parse(f.premiumExpiry?.stringValue || '');
  return f.isPremium?.booleanValue === true && !!f.purchaseToken?.stringValue && exp && exp + 3 * 864e5 > Date.now();
}

function rateLimited(uid, limit) {
  const k = uid + ':' + Math.floor(Date.now() / 3600e3);
  const n = (hits.get(k) || 0) + 1; hits.set(k, n);
  if (hits.size > 5000) hits.clear();
  return n > limit;
}

const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Authorization, Content-Type', 'Access-Control-Allow-Methods': 'POST, OPTIONS' };
const json = (status, obj) => new Response(JSON.stringify(obj), { status, headers: { ...CORS, 'Content-Type': 'application/json' } });

export default {
  async fetch(req, env) {
    const PROJECT_ID = env.FIREBASE_PROJECT_ID || 'rosiva-929c8';
    const MODELS = (env.GROQ_MODELS || 'openai/gpt-oss-120b,qwen/qwen3.8-27b,openai/gpt-oss-20b').split(',');
    if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    if (req.method === 'GET') return json(200, { ok: true, service: 'lamsa-ai', host: 'cloudflare', configured: !!env.GROQ_API_KEY });
    if (req.method !== 'POST') return json(405, { error: { code: 'method' } });
    if (!env.GROQ_API_KEY) return json(503, { error: { code: 'not_configured', message: 'GROQ_API_KEY missing' } });

    const idToken = (req.headers.get('authorization') || '').replace(/^Bearer\s+/i, '');
    let uid;
    try { uid = await verifyIdToken(idToken, PROJECT_ID); } catch { return json(401, { error: { code: 'auth', message: 'sign in required' } }); }
    if (!(await vipStatus(uid, idToken, PROJECT_ID))) return json(402, { error: { code: 'vip_required', message: 'VIP subscription required' } });
    if (rateLimited(uid, Number(env.HOURLY_LIMIT || 60))) return json(429, { error: { code: 'rate_limit', message: 'too many requests' } });

    let b; try { b = await req.json(); } catch { b = {}; }
    if (!Array.isArray(b.messages) || !b.messages.length) return json(400, { error: { code: 'bad_request' } });
    const body = {
      messages: b.messages.slice(-24),
      max_tokens: Math.min(Number(b.max_tokens) || 800, MAX_TOKENS),
      temperature: typeof b.temperature === 'number' ? b.temperature : 0.6,
    };
    if (Array.isArray(b.tools)) { body.tools = b.tools; body.tool_choice = b.tool_choice || 'auto'; }

    let last = null;
    for (const model of MODELS) {
      try {
        const r = await fetch('https://api.groq.com/openai/v1/chat/completions', {
          method: 'POST',
          headers: { Authorization: 'Bearer ' + env.GROQ_API_KEY, 'Content-Type': 'application/json', 'User-Agent': 'lamsa-ai/1.0' },
          body: JSON.stringify({ ...body, model }),
        });
        const d = await r.json().catch(() => ({}));
        if (r.ok) return json(200, d);
        last = { status: r.status, d };
        if (![404, 400, 429, 500, 502, 503].includes(r.status)) break;
      } catch (e) { last = { status: 502, d: { error: { message: String(e) } } }; }
    }
    return json(last?.status === 429 ? 429 : 502, { error: { code: 'upstream', message: last?.d?.error?.message || 'AI unavailable' } });
  },
};
