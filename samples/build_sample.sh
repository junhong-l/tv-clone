#!/bin/bash
# 构建测试样本 APK（用于验证克隆流水线，不是交付物）
set -euo pipefail
SDK=${SDK:-/opt/android-sdk}
BT=$SDK/build-tools/36.0.0
AJ=$SDK/platforms/android-34/android.jar
HERE=$(cd "$(dirname "$0")" && pwd)
APP=$HERE/demoapp
OUT=${OUT:-$HERE/demo-tvplayer.apk}
KS=$HERE/demo.jks

cd "$APP"
rm -rf build
mkdir -p build/gen build/classes build/dex "res/mipmap-xxhdpi"

[ -f res/mipmap-xxhdpi/ic_launcher.png ] || python3 "$HERE/make_icon.py" res/mipmap-xxhdpi/ic_launcher.png

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$AJ" --manifest AndroidManifest.xml build/res.zip \
    --java build/gen --min-sdk-version 23 --target-sdk-version 33 \
    --version-code 7 --version-name 1.2.3
javac --release 8 -nowarn -cp "$AJ" -d build/classes $(find build/gen src -name '*.java')
"$BT/d8" --min-api 23 --lib "$AJ" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/app.apk
(cd build/dex && zip -q ../app.apk classes.dex)

[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -alias demo -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass demopass -keypass demopass -dname "CN=Demo" >/dev/null 2>&1

"$BT/zipalign" -f -P 16 4 build/app.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:demopass --key-pass pass:demopass \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
    --out "$OUT" build/aligned.apk
echo "== built: $OUT"
"$BT/aapt2" dump badging "$OUT" | head -3
