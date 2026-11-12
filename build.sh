#!/usr/bin/env bash
# Build the Ad Cover APK with the plain Android SDK command-line tools (no Gradle).
# Needs: JDK 17 at ~/.local/share/jdk17, SDK at ~/.local/share/android-sdk with
#        build-tools;34.0.0 and platforms;android-33 installed (API 30+ for accessibility screenshots;
#        the app still runs on Android 8+, minSdk 26, and checks the version at runtime).
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdk17}"
SDK="${ANDROID_SDK:-$HOME/.local/share/android-sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-33/android.jar"
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest AndroidManifest.xml -R "$OUT/res.zip" --auto-add-overlay
"$JAVA_HOME/bin/javac" -encoding UTF-8 -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR" -classpath "$JAR" \
    -d "$OUT/classes" $(find src -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1],'a').write(sys.argv[2],'classes.dex')" "$OUT/unsigned.apk" "$OUT/dex/classes.dex"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Signing key: your own keystore (created on first build). Set ADCOVER_KS / ADCOVER_KS_PASS to use another.
KS="${ADCOVER_KS:-$HOME/.local/share/adcover.keystore}"
PASS="${ADCOVER_KS_PASS:-adcover}"
if [ ! -f "$KS" ]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" \
        -alias adcover -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Ad Cover" >/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
    --out adcover.apk "$OUT/aligned.apk"
echo "built $(pwd)/adcover.apk"
