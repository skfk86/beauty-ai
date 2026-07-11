# وثيقة تقنية شاملة — Beauty AI (Rosiva)

## 1. نظرة عامة
تطبيق موبايل (Android) في مجال الجمال والعناية الشخصية، بيعرض كتالوج منتجات (ميك أب، عناية بالبشرة، عطور، عناية بالشعر، عناية بالجسم) مقسّم حسب البراند والفئة، ومزوّد بمستشار ذكاء اصطناعي يجاوب على أسئلة المستخدم عن المنتجات باللغة العربية والإنجليزية.

- **الاسم الحالي:** Rosiva (`com.rosiva.app`) — قابل للتغيير بالكامل قبل النشر.
- **المنصة:** Android (عبر Capacitor)، مبني من كود ويب واحد (HTML/CSS/JS).
- **اللغات المدعومة:** عربي (افتراضي، RTL) + إنجليزي، مع نظام ترجمة تلقائي للبحث.

## 2. المميزات الرئيسية

| الميزة | الوصف |
|---|---|
| كتالوج منتجات | تصنيف حسب 5 فئات رئيسية (Makeup, Skincare, Fragrances, Hair, Body) و+30 براند مشهور (Dior, Chanel, MAC, Fenty, La Mer... إلخ) |
| مستشار AI | شات بوت بيجاوب على أسئلة عن أي منتج (الاستخدام، المكونات، البدائل، السعر) عبر Groq API |
| بحث ذكي | بحث بالعربي بيتترجم أوتوماتيك للإنجليزي قبل الاستعلام عن المنتجات |
| تقييمات ومراجعات | كل منتج له تقييم (rating) وعدد مراجعات ومراجعات نصية من مستخدمين |
| دعم لغتين (i18n) | +90 مفتاح ترجمة (data-i18n) تبديل فوري عربي/إنجليزي مع دعم RTL/LTR |
| مزامنة تلقائية للمنتجات | سكربت Node.js يسحب بيانات منتجات حقيقية من OpenBeautyFacts API ويخزنها في Firestore أسبوعيًا عبر GitHub Actions (cron) |
| تخزين سحابي | Firebase (Auth + Firestore) لتخزين المنتجات وبيانات المستخدمين |

## 3. التقنيات المستخدمة (Tech Stack)

- **الواجهة (Frontend):** HTML/CSS/JavaScript خام (Vanilla JS) — بدون framework، ملف واحد رئيسي `www/index.html` (~5850 سطر)
- **تغليف الموبايل:** [Capacitor](https://capacitorjs.com) v6 (`@capacitor/android`, `@capacitor/core`, `@capacitor/cli`)
- **إضافات Capacitor:** `@capacitor-community/text-to-speech`
- **الذكاء الاصطناعي:** Groq API (نماذج: `llama-3.1-8b-instant`, `llama-3.3-70b-versatile`, `llama-4-scout`, `llama-4-maverick`) مع نظام fallback بين النماذج
- **قاعدة البيانات / Backend:** Firebase (Firestore + Auth + Analytics)
- **مصدر بيانات المنتجات:** OpenBeautyFacts API (مجاني، مفتوح المصدر)
- **CI/CD:** GitHub Actions — 2 workflows:
  - `build-android.yml`: بناء AAB وتوقيعه ورفعه كـ Release تلقائيًا عند كل push
  - `sync-products.yml`: مزامنة أسبوعية للمنتجات من OpenBeautyFacts إلى Firestore
- **البناء الأندرويد:** Gradle + JDK 17 + Android SDK

## 4. هيكل المشروع

```
beauty-ai-master/
├── www/
│   ├── index.html          # التطبيق الكامل (UI + منطق + AI + i18n)
│   ├── landing.html        # صفحة هبوط/تعريفية
│   └── assets/             # شعار وصور
├── android/                 # مشروع Capacitor Android الكامل
│   └── app/build.gradle    # إعدادات التوقيع والـ applicationId
├── scripts/
│   └── sync-products.js    # سكربت سحب ومزامنة المنتجات
├── icons/                   # أيقونات التطبيق بمقاسات مختلفة + سكربت توليد
├── .github/workflows/
│   ├── build-android.yml
│   └── sync-products.yml
├── capacitor.config.json   # appId, appName, webDir
├── package.json
└── SETUP_FOR_BUYER.md      # دليل الإعداد بعد الشراء
```

## 5. آلية حقن المفاتيح (Secrets Injection)

الكود المسلَّم **لا يحتوي على أي مفاتيح أو بيانات حقيقية** — كل القيم الحساسة عبارة عن placeholders بيتم استبدالها تلقائيًا وقت الـ build عبر GitHub Actions:

| Placeholder | GitHub Secret المطلوب |
|---|---|
| `__GROQ_API_KEY__` | `GROQ_API_KEY` |
| `__FIREBASE_API_KEY__` | `FIREBASE_API_KEY` |
| `__FIREBASE_AUTH_DOMAIN__` | `FIREBASE_AUTH_DOMAIN` |
| `__FIREBASE_PROJECT_ID__` | `FIREBASE_PROJECT_ID` |
| `__FIREBASE_STORAGE_BUCKET__` | `FIREBASE_STORAGE_BUCKET` |
| `__FIREBASE_MESSAGING_SENDER_ID__` | `FIREBASE_MESSAGING_SENDER_ID` |
| `__FIREBASE_APP_ID__` | `FIREBASE_APP_ID` |
| `__FIREBASE_MEASUREMENT_ID__` | `FIREBASE_MEASUREMENT_ID` |
| Keystore | `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` |
| مزامنة المنتجات | `FIREBASE_SERVICE_ACCOUNT` (JSON كامل لحساب خدمة Firebase Admin) |

بمجرد إضافة الـ secrets دي في إعدادات الـ repo (Settings → Secrets and variables → Actions)، أي push هيبني نسخة AAB جاهزة للنشر تلقائيًا من غير أي تعديل يدوي في الكود.

## 6. خطوات الإعداد بعد الشراء (ملخص)
راجع `SETUP_FOR_BUYER.md` للتفاصيل الكاملة خطوة بخطوة. الخطوات الأساسية:
1. إنشاء مفتاح Groq API جديد.
2. إنشاء مشروع Firebase جديد (Firestore + Auth) وإضافة الـ 7 secrets.
3. توليد Android keystore جديد وإضافته كـ secret.
4. تغيير `applicationId` واسم التطبيق والأيقونات في `android/app/build.gradle` و `capacitor.config.json` و `icons/`.
5. مراجعة Firestore Security Rules قبل النشر.

## 7. ما هو غير مُتضمَّن في هذا التسليم
- لا يوجد Android keystore (لأسباب أمنية — لازم يتولد جديد من المشتري).
- لا يوجد مفاتيح API حقيقية (Groq/Firebase) — كلها placeholders.
- لا توجد بيانات دفع أو معلومات شخصية.
- لا يوجد حساب Google Play Console أو نشر فعلي — التسليم كود مصدري فقط.
