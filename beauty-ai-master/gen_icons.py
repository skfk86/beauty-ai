#!/usr/bin/env python3
"""
يولّد أيقونات الأندرويد (ic_launcher.png + ic_launcher_round.png)
لكل كثافة شاشة من شعار Rosiva، بدون الحاجة لـ npm أو إنترنت.

الاستخدام:
    python3 gen_icons.py <مسار_الشعار> <مسار_مجلد_res>

مثال على Termux:
    python3 gen_icons.py www/assets/rosiva-logo.jpg android/app/src/main/res
"""
import sys
import os
from PIL import Image, ImageDraw

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

def make_round(img, size):
    """يقص الصورة على شكل دائرة لنسخة ic_launcher_round"""
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size, size), fill=255)
    rounded = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    rounded.paste(img, (0, 0), mask)
    return rounded

def main():
    if len(sys.argv) != 3:
        print("الاستخدام: python3 gen_icons.py <مسار_الشعار> <مسار_مجلد_res>")
        sys.exit(1)

    logo_path = sys.argv[1]
    res_path = sys.argv[2]

    if not os.path.isfile(logo_path):
        print(f"❌ الملف غير موجود: {logo_path}")
        sys.exit(1)
    if not os.path.isdir(res_path):
        print(f"❌ المجلد غير موجود: {res_path}")
        sys.exit(1)

    base = Image.open(logo_path).convert("RGBA")
    # نجعلها مربعة عن طريق القص من المنتصف (الشعار أصلاً مربع 1080x1080)
    w, h = base.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    base = base.crop((left, top, left + side, top + side))

    for folder, size in SIZES.items():
        target_dir = os.path.join(res_path, folder)
        os.makedirs(target_dir, exist_ok=True)

        square = base.resize((size, size), Image.LANCZOS)
        square.save(os.path.join(target_dir, "ic_launcher.png"))

        # نسخة دائرية مع padding بسيط حتى لا تُقطع الزوايا المهمة
        padded_size = int(size * 0.9)
        padded = base.resize((padded_size, padded_size), Image.LANCZOS)
        canvas = Image.new("RGBA", (size, size), (14, 5, 18, 255))  # خلفية بنفس لون التطبيق
        offset = (size - padded_size) // 2
        canvas.paste(padded, (offset, offset), padded)
        round_icon = make_round(canvas, size)
        round_icon.save(os.path.join(target_dir, "ic_launcher_round.png"))

        # نسخة foreground للأيقونة المتكيفة (adaptive icon) - تستخدمها بعض أجهزة الأندرويد
        fg_size = int(size * 1.5)
        fg_logo_size = int(size * 0.9)
        fg_canvas = Image.new("RGBA", (fg_size, fg_size), (0, 0, 0, 0))
        fg_logo = base.resize((fg_logo_size, fg_logo_size), Image.LANCZOS)
        fg_offset = (fg_size - fg_logo_size) // 2
        fg_canvas.paste(fg_logo, (fg_offset, fg_offset), fg_logo)
        fg_canvas.save(os.path.join(target_dir, "ic_launcher_foreground.png"))

        print(f"✔ {folder}: ic_launcher.png / ic_launcher_round.png / ic_launcher_foreground.png ({size}px)")

    print("\n✅ تم توليد جميع الأيقونات بنجاح.")
    print("لا تنسى تشغيل: npx cap sync android (بدون assets/sharp) ثم إعادة بناء المشروع.")

if __name__ == "__main__":
    main()
