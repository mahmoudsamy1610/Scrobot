#!/usr/bin/env bash
# Builds and signs app/build/outputs/apk/release/claude-hands-<version>.apk WITHOUT the Android
# Gradle Plugin or the Google SDK downloads, using only:
#   - Ubuntu packages:  aapt2, dx (dalvik-exchange), zipalign, apksigner, a JDK, Gradle
#   - Maven Central:    Robolectric's android-all jars (the real framework classes and
#                       resources.arsc) and the Kotlin compiler/stdlib.
#     Code compiles against Android 16 (API 36). Resources link against the Android 14 table
#     because Ubuntu's aapt2 cannot read the newer table format; public resource IDs never change.
# This is how the released APK was produced. With a normal Android SDK, use `./gradlew assembleRelease`.
#
#   sudo apt-get install aapt apksigner zipalign dalvik-exchange
#   tools/build-offline.sh
#
# Signing: keystore/keystore.properties (storeFile, storePassword, keyAlias, keyPassword).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION_NAME="1.0"
VERSION_CODE=1
MIN_SDK=26
TARGET_SDK=36
PACKAGE="com.mahmoud.claudehands"
ANDROID_ALL_VERSION="16-robolectric-13921718"   # compile classpath (API 36)
ANDROID_RES_VERSION="14-robolectric-10818077"   # framework resources for aapt2 (API 34)

SDK_DIR="${SDK_DIR:-$HOME/.cache/claude-hands-sdk}"
BUILD="$ROOT/app/build/offline"
OUT="$ROOT/app/build/outputs/apk/release"
DX="${DX:-$(command -v dx || echo /usr/lib/android-sdk/build-tools/debian/dx)}"

mkdir -p "$SDK_DIR" "$OUT"
rm -rf "$BUILD"
mkdir -p "$BUILD"/{res,gen,dex}

fetch() {
  local jar="$SDK_DIR/android-all-$1.jar"
  if [ ! -f "$jar" ]; then
    echo "== downloading android-all $1 (~190 MB)" >&2
    curl -fSL -o "$jar.part" "https://repo1.maven.org/maven2/org/robolectric/android-all/$1/android-all-$1.jar"
    mv "$jar.part" "$jar"
  fi
  echo "$jar"
}
ANDROID_JAR="$(fetch "$ANDROID_ALL_VERSION")"
ANDROID_RES_JAR="$(fetch "$ANDROID_RES_VERSION")"

echo "== resources (aapt2)"
# The source manifest has no package attribute (AGP sets it from `namespace`); add it here.
sed "s#<manifest #<manifest package=\"$PACKAGE\" #" "$ROOT/app/src/main/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"
aapt2 compile --dir "$ROOT/app/src/main/res" -o "$BUILD/res/compiled.zip"
aapt2 link -o "$BUILD/resources.apk" \
  -I "$ANDROID_RES_JAR" \
  --manifest "$BUILD/AndroidManifest.xml" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$BUILD/gen" \
  "$BUILD/res/compiled.zip"

echo "== kotlin"
gradle -q -p "$ROOT/tools/offline" --no-daemon \
  -PandroidJar="$ANDROID_JAR" -PgenDir="$BUILD/gen" \
  clean jar copyRuntime
APP_JAR="$ROOT/tools/offline/build/libs/claude-hands-offline.jar"
mkdir -p "$BUILD/libs"
for jar in "$ROOT"/tools/offline/build/runtime/*.jar; do
  # dx predates Java 9: drop module-info and multi-release classes.
  name="$(basename "$jar")"
  cp "$jar" "$BUILD/libs/$name"
  zip -q -d "$BUILD/libs/$name" 'module-info.class' 'META-INF/versions/*' >/dev/null 2>&1 || true
done
cp "$APP_JAR" "$BUILD/libs/app.jar"
zip -q -d "$BUILD/libs/app.jar" 'META-INF/*' >/dev/null 2>&1 || true

echo "== dex (dx)"
"$DX" --dex --min-sdk-version="$MIN_SDK" --output="$BUILD/dex/classes.dex" "$BUILD"/libs/*.jar

echo "== package"
cp "$BUILD/resources.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q -X "$BUILD/unsigned.apk" classes.dex)
zipalign -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "== sign"
PROPS="$ROOT/keystore/keystore.properties"
prop() { grep -E "^$1=" "$PROPS" | head -1 | cut -d= -f2-; }
STORE_FILE="$ROOT/keystore/$(prop storeFile)"
APK="$OUT/claude-hands-$VERSION_NAME.apk"
apksigner sign \
  --ks "$STORE_FILE" --ks-key-alias "$(prop keyAlias)" \
  --ks-pass "pass:$(prop storePassword)" --key-pass "pass:$(prop keyPassword)" \
  --min-sdk-version "$MIN_SDK" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$BUILD/aligned.apk"
apksigner verify --verbose --min-sdk-version "$MIN_SDK" "$APK" | head -5
echo "== done: $APK"
