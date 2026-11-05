"""
يجلب أفضل المنتجات من المتاجر الرسمية للبراندات (Shopify) مع صورها الأصلية.
يعمل على GitHub Actions. الناتج: data/shop_raw.json + www/assets/products/*.webp
"""
import json, re, io, os, sys, time, html, urllib.request
from PIL import Image

STORES_V1 = "see git history"
STORES = [  # الدفعة الثانية — براندات جديدة
 ("Milk Makeup","milkmakeup.com","makeup"), ("Haus Labs","www.hauslabs.com","makeup"),
 ("ONE/SIZE","onesizebeauty.com","makeup"), ("Danessa Myricks","danessamyricksbeauty.com","makeup"),
 ("Kulfi","kulfibeauty.com","makeup"), ("Live Tinted","livetinted.com","makeup"),
 ("Laneige","us.laneige.com","skincare"), ("Supergoop!","supergoop.com","skincare"),
 ("The INKEY List","www.theinkeylist.com","skincare"), ("Youth To The People","www.youthtothepeople.com","skincare"),
 ("Biossance","biossance.com","skincare"), ("Dieux","dieuxskin.com","skincare"),
 ("Medicube","medicube.us","skincare"), ("Skin1004","skin1004.com","skincare"),
 ("Bread Beauty","breadbeautysupply.com","haircare"), ("K18","www.k18hair.com","haircare"),
 ("amika","loveamika.com","haircare"), ("Color Wow","colorwowhair.com","haircare"),
 ("Fable & Mane","fableandmane.com","haircare"), ("Pattern","patternbeauty.com","haircare"),
 ("Ellis Brooklyn","ellisbrooklyn.com","fragrances"), ("Snif","snif.co","fragrances"),
 ("Maison Louis Marie","maisonlouismarie.com","fragrances"), ("Henry Rose","henryrose.com","fragrances"),
 ("Commodity","commodityfragrances.com","fragrances"), ("Rare Beauty","www.rarebeauty.com","makeup"),
]
SKIP_TITLES = set(json.load(open("data/shop_raw.json"))) if False else {p["title"] for p in json.load(open("data/shop_raw.json"))}
PER_BRAND = int(os.environ.get("PER_BRAND","8"))
SKIP = re.compile(r"short-?dated|\(.*dated|gift|bundle|\bset\b|\bkit\b|duo|trio|value|mini|sample|card|tote|bag|merch|hoodie|shirt|\bhat\b|candle|refill|travel|discovery|vault|collection|box|sticker|accessor|brush case|mirror|keychain|subscription|e-?gift|warranty|shipping|protection|insurance|donation", re.I)
UA = {"User-Agent":"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129 Safari/537.36","Accept":"application/json,*/*"}
OUT_IMG = "www/assets/products"; os.makedirs(OUT_IMG, exist_ok=True)

def get(url, binary=False, tries=2):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=25) as r:
                b = r.read(); return b if binary else json.loads(b)
        except Exception as e:
            err = e; time.sleep(1.5)
    raise err

def clean(t):
    t = re.sub(r"<[^>]+>", " ", t or ""); t = html.unescape(t)
    return re.sub(r"\s+", " ", t).strip()

def slug(s): return re.sub(r"[^a-z0-9]+","-",s.lower()).strip("-")[:60]

out, report = [], []
for brand, dom, defcat in STORES:
    prods = None
    for path in ("/collections/best-sellers/products.json","/collections/bestsellers/products.json",
                 "/collections/best-seller/products.json","/collections/all/products.json","/products.json"):
        try:
            d = get(f"https://{dom}{path}?limit=250")
            if d.get("products"): prods, src = d["products"], path; break
        except Exception: pass
    if not prods: report.append(f"FAIL {brand}"); continue
    n = 0
    for p in prods:
        title = p.get("title","").strip()
        if title in SKIP_TITLES: continue
        if not title or SKIP.search(title) or SKIP.search(p.get("product_type","") or ""): continue
        vs = p.get("variants") or []
        if vs and not any(v.get("available", True) for v in vs): continue
        imgs = p.get("images") or []
        if not imgs: continue
        iurl = imgs[0]["src"].split("?")[0]
        try:
            raw = get(iurl + "?width=1000", binary=True)
            im = Image.open(io.BytesIO(raw)).convert("RGB")
        except Exception: continue
        if min(im.size) < 400: continue
        im.thumbnail((800, 800), Image.LANCZOS)
        canvas = Image.new("RGB", (800, 800), (255,255,255))   # مربع موحّد بخلفية بيضاء
        canvas.paste(im, ((800-im.width)//2, (800-im.height)//2))
        fid = f"{slug(brand)}--{slug(p.get('handle') or title)}"
        canvas.save(f"{OUT_IMG}/{fid}.webp", "WEBP", quality=80, method=6)
        price = None
        try: price = float(vs[0]["price"]) if vs else None
        except Exception: pass
        out.append({"id": fid, "brand": brand, "title": title, "type": p.get("product_type",""),
                    "tags": p.get("tags") if isinstance(p.get("tags"), list) else str(p.get("tags","")).split(","),
                    "desc": clean(p.get("body_html"))[:600], "defcat": defcat,
                    "url": f"https://{dom}/products/{p.get('handle')}", "img": f"assets/products/{fid}.webp",
                    "img_src": iurl, "price_usd": price, "src": src})
        n += 1
        if n >= PER_BRAND: break
    report.append(f"OK {brand}: {n} from {src}")
    print(report[-1], flush=True)

json.dump(out, open("data/shop_raw2.json","w"), ensure_ascii=False, indent=1)
open("data/shop_report2.txt","w").write("\n".join(report)+f"\nTOTAL {len(out)}\n")
print("\n".join(report)); print("TOTAL", len(out))
