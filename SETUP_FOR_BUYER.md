# إعداد المشروع بعد الشراء (Beauty AI / Rosiva source)

هذا الكود بيع كمصدر برمجي فقط. المفتاح القديم اتلغى (revoked) من طرف البائع، لازم تعمل الآتي قبل أي build:

## 1. مفتاح Groq API
- اعمل حساب على https://console.groq.com واحصل على مفتاح API جديد.
- في GitHub repo بتاعك: Settings → Secrets and variables → Actions → أضف secret باسم `GROQ_API_KEY`.
- الـ workflow (`.github/workflows/build-android.yml`) بيستبدل `__GROQ_API_KEY__` تلقائيًا وقت الـ build، مفيش حاجة تتعدل يدويًا في الكود.

## 2. Firebase
- الكود مفيهوش أي مشروع Firebase حقيقي — `firebaseConfig` في `www/index.html` كله placeholders (`__FIREBASE_API_KEY__`... إلخ) بتتحقن أوتوماتيك وقت الـ build، زي مفتاح Groq بالظبط.
- اعمل مشروع Firebase جديد خاص بيك (Firestore + أي خدمات مستخدمة)، وهات القيم السبعة من `Project settings → General → Your apps → SDK setup and configuration`.
- ضيفهم كـ GitHub Secrets بنفس الأسماء دي:
  `FIREBASE_API_KEY`, `FIREBASE_AUTH_DOMAIN`, `FIREBASE_PROJECT_ID`, `FIREBASE_STORAGE_BUCKET`, `FIREBASE_MESSAGING_SENDER_ID`, `FIREBASE_APP_ID`, `FIREBASE_MEASUREMENT_ID`.
- الـ workflow هيحقنهم تلقائيًا، مفيش أي تعديل يدوي مطلوب في الكود.
- راجع Firestore Security Rules كويس قبل النشر عشان تتأكد إنها مش public read/write (الـ rules المقترحة موجودة كـ تعليق جنب `firebaseConfig` في `www/index.html`).

## 3. Android keystore
- **مفيش keystore متضمن في هذا التسليم.** لازم تولّد keystore خاص بيك:
  ```
  keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
  ```
- حوّله base64 وضيفه كـ GitHub Secret حسب المتغيرات اللي بيستخدمها `build-android.yml`.

## 4. اسم الحزمة والهوية
- الـ `applicationId` الحالي هو `com.rosiva.app` واسم التطبيق "Rosiva" — دول مرتبطين بحساب Play Console بتاع البائع.
- لازم تغيّر `applicationId` في `android/app/build.gradle` و `capacitor.config.json`، وكذلك اسم التطبيق والأيقونات (icons/) لو هتنشره باسم/هوية مختلفة.

## 5. عام
- راجع `scripts/sync-products.js` و`.github/workflows/sync-products.yml` لفهم مصدر بيانات المنتجات وتحديثها حسب حسابك.
- لا توجد بيانات شخصية أو مفاتيح دفع (OxaPay/إلخ) مضمّنة في هذا الكود.
