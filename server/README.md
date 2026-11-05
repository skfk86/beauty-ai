# Lamsa AI server (Vercel)

يمرّر طلبات الذكاء الاصطناعي من التطبيق إلى Groq بعد التحقق من:
1. هوية المستخدمة (Firebase ID token، بمفاتيح Google العامة)
2. اشتراك VIP فعّال (Firestore `users/{uid}`)

- المشروع على Vercel: `lamsa-ai` — الرابط: https://lamsa-ai-dun.vercel.app/api/ai
- متغيرات البيئة: `GROQ_API_KEY` (إلزامي)، `GROQ_MODELS` (اختياري، مفصولة بفواصل)، `HOURLY_LIMIT` (افتراضي 60)
- عند سحب Groq لنموذج: عدّلي `GROQ_MODELS` من إعدادات Vercel، دون تحديث التطبيق.
