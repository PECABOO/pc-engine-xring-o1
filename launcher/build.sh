#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# WinPlay Launcher 构建脚本（无 Android SDK / 无 Gradle / 无网络）
#
# 用到的现成工具（本机已存在）：
#   $HOME/opt/android/android-14/               build-tools：aapt2 d8 zipalign apksigner
#   $HOME/opt/android-platform/android-35/       platform-35 的 android.jar
#   /usr/bin/javac                               openjdk 17
#
# 流程：aapt2 compile res -> aapt2 link (-A assets) -> javac -> d8 -> zip 塞 dex
#       -> zipalign -> apksigner(v2)
# ---------------------------------------------------------------------------
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

BT="${BT:-$HOME/opt/android/android-14}"
SDK_JAR="${SDK_JAR:-$HOME/opt/android-platform/android-35/android.jar}"
PKG=com.dsh.winplaylauncher
APK_NAME=WinPlayLauncher.apk

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$SDK_JAR"; do
    [ -e "$f" ] || { echo "缺少工具: $f"; exit 1; }
done

echo "== 0. 清理"
rm -rf build
mkdir -p build/res build/classes build/dex build/gen

echo "== 1. aapt2 compile"
"$BT/aapt2" compile --dir res -o build/res/compiled.zip

# 脚本唯一来源是 scripts/，构建时同步进 assets/（避免两份漂移）
mkdir -p assets
cp -f scripts/*.sh assets/
ls -l assets/ | sed 's/^/   /'

echo "== 2. aapt2 link（含 assets/）"
"$BT/aapt2" link \
    -o build/base.apk \
    -I "$SDK_JAR" \
    --manifest AndroidManifest.xml \
    -A assets \
    --java build/gen \
    --min-sdk-version 30 \
    --target-sdk-version 35 \
    --version-code 1 \
    --version-name 1.0 \
    build/res/compiled.zip

echo "== 3. javac (source/target 8, bootclasspath = android.jar)"
find src build/gen -name '*.java' > build/sources.txt
javac -nowarn -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$SDK_JAR" \
    -d build/classes \
    @build/sources.txt 2>&1 | grep -v 'bootstrap class path' || true

echo "== 4. d8 -> classes.dex（min-api 30）"
find build/classes -name '*.class' > build/classes.txt
"$BT/d8" --lib "$SDK_JAR" --min-api 30 --release \
    --output build/dex \
    $(cat build/classes.txt)

echo "== 5. 把 classes.dex 塞进 APK"
cp build/base.apk build/unsigned.apk
( cd build/dex && zip -q -X ../unsigned.apk classes.dex )
unzip -l build/unsigned.apk | tail -8

echo "== 6. zipalign"
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

echo "== 7. 签名（首次运行自动生成 keystore）"
KS="${KS:-$HERE/winplay.jks}"
KS_PASS="${KS_PASS:-winplay-dev-pass}"   # 只用于本地自签名，仓库里不含密钥
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias winplay \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=WinPlay Launcher, OU=DSH, O=DSH, L=Beijing, ST=Beijing, C=CN" >/dev/null 2>&1
    echo "   已生成 $KS"
fi
"$BT/apksigner" sign \
    --ks "$KS" --ks-key-alias winplay \
    --ks-pass pass:"$KS_PASS" --key-pass pass:"$KS_PASS" \
    --min-sdk-version 30 \
    --out "$APK_NAME" build/aligned.apk

echo "== 8. 校验"
"$BT/apksigner" verify --print-certs "$APK_NAME" | head -5
ls -l "$APK_NAME"
echo "== 完成: $HERE/$APK_NAME"
