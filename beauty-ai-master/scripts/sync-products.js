#!/usr/bin/env node
/**
 * Rosiva - سكربت مزامنة المنتجات
 * يسحب المنتجات من OpenBeautyFacts ويخزّنها بـ Firestore
 * يشتغل تلقائياً من GitHub Actions كل أسبوع
 */

const { initializeApp, cert } = require('firebase-admin/app');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');

// === إعداد Firebase Admin ===
const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
initializeApp({ credential: cert(serviceAccount) });
const db = getFirestore();

// === الفئات والبراندات المطلوب سحبها ===
const CATEGORIES = [
  { key: 'makeup',     query: 'make-up',        label: 'Makeup'     },
  { key: 'skincare',   query: 'skin-care',       label: 'Skincare'   },
  { key: 'fragrances', query: 'fragrances',      label: 'Fragrances' },
  { key: 'haircare',   query: 'hair-care',       label: 'Hair'       },
  { key: 'bodycare',   query: 'body-care',       label: 'Body'       },
];

const BRANDS = [
  'maybelline', 'loreal', 'nyx', 'mac', 'fenty-beauty',
  'huda-beauty', 'charlotte-tilbury', 'urban-decay', 'too-faced',
  'dior', 'chanel', 'ysl', 'lancome', 'givenchy',
  'clinique', 'estee-lauder', 'shiseido', 'sk-ii',
  'rare-beauty', 'glossier', 'kylie-cosmetics',
  'moroccanoil', 'olaplex', 'kerastase',
  'neutrogena', 'cerave', 'la-roche-posay', 'vichy', 'the-ordinary',
  'catrice', 'essence', 'wet-n-wild',
];

const PAGE_SIZE    = 50;  // منتجات بكل طلب API
const MAX_PER_CAT  = 200; // حد المنتجات لكل فئة
const MAX_PER_BRAND= 50;  // حد المنتجات لكل براند
const FIELDS = 'code,product_name,brands,brands_tags,categories_tags,image_front_display_url,image_url,ingredients_text,labels_tags';

// === أداة Fetch مع Retry ===
async function fetchJSON(url, retries = 3) {
  for (let i = 0; i < retries; i++) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(15000) });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return await res.json();
    } catch (e) {
      if (i === retries - 1) throw e;
      await new Promise(r => setTimeout(r, 2000 * (i + 1)));
    }
  }
}

// === تنظيف وتوحيد بيانات المنتج ===
function normalizeProduct(p, category) {
  const name = (p.product_name || '').trim();
  if (!name) return null;

  const img = p.image_front_display_url || p.image_url || '';
  if (!img || !img.startsWith('http')) return null;

  const brand = (p.brands || '').split(',')[0].trim();
  const brandSlug = brand.toLowerCase().replace(/[^a-z0-9]+/g, '-');

  return {
    code:        p.code || '',
    name,
    brand,
    brand_slug:  brandSlug,
    category:    category || detectCategory(p),
    image_url:   img,
    ingredients: (p.ingredients_text || '').substring(0, 500),
    labels:      (p.labels_tags || []).slice(0, 10),
    updated_at:  Timestamp.now(),
    source:      'openbeautyfacts',
  };
}

function detectCategory(p) {
  const cats = (p.categories_tags || []).join(' ').toLowerCase();
  if (cats.includes('make-up') || cats.includes('makeup'))    return 'makeup';
  if (cats.includes('skin-care') || cats.includes('skincare')) return 'skincare';
  if (cats.includes('fragrance'))                              return 'fragrances';
  if (cats.includes('hair'))                                   return 'haircare';
  return 'other';
}

// === سحب منتجات فئة معينة ===
async function fetchByCategory(cat) {
  const products = [];
  let page = 1;
  console.log(`  📦 فئة: ${cat.label}`);

  while (products.length < MAX_PER_CAT) {
    const url = `https://world.openbeautyfacts.org/cgi/search.pl?action=process&tagtype_0=categories&tag_contains_0=contains&tag_0=${encodeURIComponent(cat.query)}&json=true&page=${page}&page_size=${PAGE_SIZE}&fields=${FIELDS}&nocache=1`;
    try {
      const data = await fetchJSON(url);
      const raw = (data.products || []);
      for (const p of raw) {
        const normalized = normalizeProduct(p, cat.key);
        if (normalized) products.push(normalized);
      }
      console.log(`    صفحة ${page}: ${raw.length} منتج خام → ${products.length} صالح`);
      if (raw.length < PAGE_SIZE) break;
      page++;
      await new Promise(r => setTimeout(r, 300));
    } catch (e) {
      console.warn(`    ⚠️ فشل الطلب: ${e.message}`);
      break;
    }
  }
  return products.slice(0, MAX_PER_CAT);
}

// === سحب منتجات براند معين ===
async function fetchByBrand(brandSlug) {
  const url = `https://world.openbeautyfacts.org/cgi/search.pl?action=process&tagtype_0=brands&tag_contains_0=contains&tag_0=${encodeURIComponent(brandSlug)}&json=true&page=1&page_size=${MAX_PER_BRAND}&fields=${FIELDS}&nocache=1`;
  try {
    const data = await fetchJSON(url);
    const products = (data.products || [])
      .map(p => normalizeProduct(p, null))
      .filter(Boolean);
    console.log(`  🏷️  براند ${brandSlug}: ${products.length} منتج`);
    return products;
  } catch (e) {
    console.warn(`  ⚠️ فشل سحب براند ${brandSlug}: ${e.message}`);
    return [];
  }
}

// === كتابة دفعات Firestore (Batch writes) ===
async function writeBatch(products) {
  const BATCH_SIZE = 400; // Firestore max 500, نأخذ هامش أمان
  let written = 0;

  for (let i = 0; i < products.length; i += BATCH_SIZE) {
    const chunk = products.slice(i, i + BATCH_SIZE);
    const batch = db.batch();

    for (const p of chunk) {
      const docId = p.code || `${p.brand_slug}_${p.name.substring(0, 30)}`.replace(/[^a-z0-9_]/gi, '_');
      const ref = db.collection('products').doc(docId);
      batch.set(ref, p, { merge: true });
    }

    await batch.commit();
    written += chunk.length;
    console.log(`  ✅ كُتب ${written}/${products.length} منتج`);
    await new Promise(r => setTimeout(r, 200));
  }
  return written;
}

// === تحديث metadata ===
async function updateMeta(stats) {
  await db.collection('_meta').doc('products_sync').set({
    last_sync:    Timestamp.now(),
    total:        stats.total,
    by_category:  stats.byCategory,
    status:       'ok',
  });
}

// === الدالة الرئيسية ===
async function main() {
  console.log('🚀 بدء مزامنة المنتجات مع Firestore...\n');

  const allProducts = new Map(); // code → product (تجنب التكرار)
  const byCategory  = {};

  // 1. سحب حسب الفئات
  console.log('📂 مرحلة 1: سحب المنتجات حسب الفئات');
  for (const cat of CATEGORIES) {
    const products = await fetchByCategory(cat);
    for (const p of products) {
      allProducts.set(p.code || Math.random().toString(36), p);
    }
    byCategory[cat.key] = products.length;
    await new Promise(r => setTimeout(r, 500));
  }

  // 2. سحب حسب البراندات
  console.log('\n📂 مرحلة 2: سحب المنتجات حسب البراندات');
  for (const brand of BRANDS) {
    const products = await fetchByBrand(brand);
    for (const p of products) {
      allProducts.set(p.code || Math.random().toString(36), p);
    }
    await new Promise(r => setTimeout(r, 300));
  }

  const uniqueProducts = Array.from(allProducts.values());
  console.log(`\n📊 إجمالي المنتجات الفريدة: ${uniqueProducts.length}`);

  // 3. الكتابة بـ Firestore
  console.log('\n💾 مرحلة 3: كتابة Firestore...');
  const written = await writeBatch(uniqueProducts);

  // 4. تحديث metadata
  await updateMeta({ total: written, byCategory });

  console.log(`\n✅ اكتملت المزامنة — ${written} منتج بـ Firestore`);
}

main().catch(err => {
  console.error('❌ فشل السكربت:', err);
  process.exit(1);
});
