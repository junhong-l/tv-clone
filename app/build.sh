#!/bin/bash
# 构建「双开管家」TV 应用（aapt2 + javac + d8 + apksigner，无 Gradle）
set -euo pipefail
SDK=${SDK:-/opt/android-sdk}
BT=$SDK/build-tools/36.0.0
AJ=$SDK/platforms/android-34/android.jar
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$HERE/build/TvDualManager.apk}
KS=${KS:-$HERE/release.jks}
KSP=${KSP:-tvdual-pass}

cd "$HERE"
rm -rf build
mkdir -p build/gen build/classes build/dex

if [ ! -f res/mipmap-xxhdpi/ic_launcher.png ] || [ ! -f res/drawable-xhdpi/banner.png ]; then
  echo "[0/6] 生成图标与 banner"
  python3 make_assets.py
fi

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o build/base.apk -I "$AJ" \
  --manifest AndroidManifest.xml build/res.zip -A assets \
  --java build/gen --min-sdk-version 24 --target-sdk-version 33 \
  --version-code 6 --version-name 2.0.4

echo "[3/6] javac"
javac --release 8 -nowarn -Xlint:-options -cp "$AJ" -d build/classes \
  $(find build/gen src core/com -name '*.java')

echo "[4/6] d8"
"$BT/d8" --min-api 23 --lib "$AJ" --output build/dex $(find build/classes -name '*.class')

echo "[5/6] 打包 + 对齐"
cp build/base.apk build/app.apk
(cd build/dex && zip -q ../app.apk classes.dex)
"$BT/zipalign" -f -P 16 4 build/app.apk build/aligned.apk

echo "[6/6] 签名"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias tvdual -keyalg RSA -keysize 2048 \
    -validity 12000 -storepass "$KSP" -keypass "$KSP" -dname "CN=TvDual, O=TvDual, C=CN"
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias tvdual \
  --ks-pass "pass:$KSP" --key-pass "pass:$KSP" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --v4-signing-enabled false --out "$OUT" build/aligned.apk

echo "== 产物: $OUT"
ls -lh "$OUT" | awk '{print $5, $9}'
"$BT/aapt2" dump badging "$OUT" | head -3
