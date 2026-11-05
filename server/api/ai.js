// Lamsa AI proxy — Vercel Serverless Function (Node 20+, no dependencies)
// 1) يتحقق من هوية المستخدمة (Firebase ID token) بمفاتيح Google العامة
// 2) يتحقق من أن اشتراك VIP فعّال وغير منتهٍ (Firestore: users/{uid})
// 3) يمرّر الطلب إلى Groq بالمفتاح المخزّن هنا فقط (لا يوجد مفتاح داخل التطبيق)
import crypto from 'node:crypto';

const PROJECT_ID = process.env.FIREBASE_PROJECT_ID || 'rosiva-929c8';
const GROQ_KEY = process.env.GROQ_API_KEY;
// أول نموذج متاح يُستخدم؛ عند سحب نموذج من Groq ننتقل للتالي تلقائياً
const MODELS = (process.env.GROQ_MODELS || 'openai/gpt-oss-120b,qwen/qwen3.8-27b,openai/gpt-oss-20b').split(',');
const HOURLY_LIMIT = Number(process.env.HOURLY_LIMIT || 60);
const MAX_TOKENS = 1500;

let certs = null, certsExp = 0;
async function googleCerts() {
  if (certs && Date.now() < certsExp) return certs;
  const r = await fetch('https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com');
  certs = await r.json();
  const m = /max-age=(\d+)/.exec(r.headers.get('cache-control') || '');
  certsExp = Date.now() + (m ? Number(m[1]) * 1000 : 3600e3);
  return certs;
}
const b64u = s => Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/'), 'base64');

async function verifyIdToken(tok) {
  const [h, p, sig] = String(tok || '').split('.');
  if (!sig) throw new Error('bad token');
  const header = JSON.parse(b64u(h)), payload = JSON.parse(b64u(p));
  const pem = (await googleCerts())[header.kid];
  if (header.alg !== 'RS256' || !pem) throw new Error('bad key');
  const ok = crypto.createVerify('RSA-SHA256').update(`${h}.${p}`).verify(crypto.createPublicKey(pem), b64u(sig));
  const now = Date.now() / 1000;
  if (!ok || payload.aud !== PROJECT_ID || payload.iss !== `https://securetoken.google.com/${PROJECT_ID}`
      || payload.exp < now || payload.iat > now + 300 || !payload.sub) throw new Error('invalid token');
  return payload.sub;
}

async function vipStatus(uid, idToken) {
  const r = await fetch(`https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents/users/${uid}`,
    { headers: { Authorization: 'Bearer ' + idToken } });
  if (!r.ok) return false;
  const f = (await r.json()).fields || {};
  const premium = f.isPremium?.booleanValue === true;
  const exp = Date.parse(f.premiumExpiry?.stringValue || '');
  const hasToken = !!f.purchaseToken?.stringValue;
  // مهلة 3 أيام بعد تاريخ التجديد (تأخر مزامنة Google Play)
  return premium && hasToken && exp && exp + 3 * 864e5 > Date.now();
}

const hits = new Map(); // حدّ تقريبي لكل مستخدمة (لكل نسخة من الخادم)
function rateLimited(uid) {
  const h = Math.floor(Date.now() / 3600e3), k = uid + ':' + h;
  const n = (hits.get(k) || 0) + 1; hits.set(k, n);
  if (hits.size > 5000) hits.clear();
  return n > HOURLY_LIMIT;
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method === 'GET') return res.status(200).json({ ok: true, service: 'lamsa-ai', configured: !!GROQ_KEY });
  if (req.method !== 'POST') return res.status(405).json({ error: { code: 'method' } });
  if (!GROQ_KEY) return res.status(503).json({ error: { code: 'not_configured', message: 'GROQ_API_KEY missing' } });

  const idToken = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  let uid;
  try { uid = await verifyIdToken(idToken); }
  catch { return res.status(401).json({ error: { code: 'auth', message: 'sign in required' } }); }
  if (!(await vipStatus(uid, idToken))) return res.status(402).json({ error: { code: 'vip_required', message: 'VIP subscription required' } });
  if (rateLimited(uid)) return res.status(429).json({ error: { code: 'rate_limit', message: 'too many requests' } });

  const b = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
  if (!Array.isArray(b.messages) || !b.messages.length) return res.status(400).json({ error: { code: 'bad_request' } });
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
        headers: { Authorization: 'Bearer ' + GROQ_KEY, 'Content-Type': 'application/json', 'User-Agent': 'lamsa-ai/1.0' },
        body: JSON.stringify({ ...body, model }),
      });
      const d = await r.json().catch(() => ({}));
      if (r.ok) return res.status(200).json(d);
      last = { status: r.status, d };
      if (![404, 400, 429, 500, 502, 503].includes(r.status)) break; // أخطاء أخرى لا تستفيد من نموذج آخر
    } catch (e) { last = { status: 502, d: { error: { message: String(e) } } }; }
  }
  return res.status(last?.status === 429 ? 429 : 502).json({ error: { code: 'upstream', message: last?.d?.error?.message || 'AI unavailable' } });
}
