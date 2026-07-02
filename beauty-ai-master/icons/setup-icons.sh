#!/bin/bash
# سكريبت تثبيت أيقونات Rosiva
# شغّله من مجلد ~/beauty-ai

BASE="android/app/src/main/res"

mkdir -p $BASE/mipmap-mdpi
mkdir -p $BASE/mipmap-hdpi  
mkdir -p $BASE/mipmap-xhdpi
mkdir -p $BASE/mipmap-xxhdpi
mkdir -p $BASE/mipmap-xxxhdpi

cp icons/mipmap-mdpi.png    $BASE/mipmap-mdpi/ic_launcher.png
cp icons/mipmap-mdpi.png    $BASE/mipmap-mdpi/ic_launcher_round.png
cp icons/mipmap-hdpi.png    $BASE/mipmap-hdpi/ic_launcher.png
cp icons/mipmap-hdpi.png    $BASE/mipmap-hdpi/ic_launcher_round.png
cp icons/mipmap-xhdpi.png   $BASE/mipmap-xhdpi/ic_launcher.png
cp icons/mipmap-xhdpi.png   $BASE/mipmap-xhdpi/ic_launcher_round.png
cp icons/mipmap-xxhdpi.png  $BASE/mipmap-xxhdpi/ic_launcher.png
cp icons/mipmap-xxhdpi.png  $BASE/mipmap-xxhdpi/ic_launcher_round.png
cp icons/mipmap-xxxhdpi.png $BASE/mipmap-xxxhdpi/ic_launcher.png
cp icons/mipmap-xxxhdpi.png $BASE/mipmap-xxxhdpi/ic_launcher_round.png
cp icons/ic_launcher_foreground.png $BASE/mipmap-xxxhdpi/ic_launcher_foreground.png

echo "✅ Icons installed successfully"
