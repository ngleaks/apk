#!/usr/bin/env bash
# Offline APK build for PBB LIVE: aapt2 -> ecj (R class) -> kotlinc -> d8 -> apksigner.
#
# No Gradle and no Android SDK download are required — the same steps Gradle runs are spelled
# out so the build works in a sandbox that can only reach github.com / npmjs / pypi.
# On a normal machine use `gradle :app:assembleRelease` instead (see README).
#
# Usage: buildtools/build_apk.sh [--debug] [--out path/to/app.apk]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/app/src/main"
BUILD="$ROOT/buildtools/build"
TYPE="release"
OUT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --debug) TYPE="debug"; shift ;;
    --out) OUT="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

: "${JAVA_HOME:?set JAVA_HOME to a JDK 17 (see README: buildtools/pylibs/jdk4py)}"
: "${ANDROID_JAR:?set ANDROID_JAR to a platform android.jar (see tools.properties)}"
: "${AAPT2:?set AAPT2 to the aapt2 binary}"
: "${D8_JAR:?set D8_JAR to d8.jar}"
: "${APKSIGNER_JAR:?set APKSIGNER_JAR to apksigner.jar}"
: "${ECJ_JAR:?set ECJ_JAR to ecj.jar (compiles the aapt2-generated R class)}"
: "${KOTLINC_HOME:?set KOTLINC_HOME to the extracted kotlin-compiler package (bin/kotlinc-jvm, lib/*.jar)}"

JAVA="$JAVA_HOME/bin/java"
KEYTOOL_JAR="${KEYTOOL_JAR:-$JAVA_HOME/lib/keytool.jar}"
KOTLINC_BIN="$KOTLINC_HOME/bin/kotlinc-jvm"
KOTLIN_STDLIB_JAR="$KOTLINC_HOME/lib/kotlin-stdlib.jar"

MIN_SDK=24
TARGET_SDK=35
VERSION_CODE="${VERSION_CODE:-200}"
VERSION_NAME="${VERSION_NAME:-2.0.0}"
PKG="ph.pbb.live"
OUT="${OUT:-$ROOT/buildtools/dist/PBB-LIVE-TV-${VERSION_NAME}-${TYPE}.apk}"

rm -rf "$BUILD"
mkdir -p "$BUILD"/{res,gen,classes,dex,dist}

echo "==> 1/6 aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$BUILD/res/resources.zip"

# aapt2 requires package=""; AGP forbids it in the source manifest, so inject into a copy.
mkdir -p "$BUILD/manifest"
python3 - "$APP/AndroidManifest.xml" "$BUILD/manifest/AndroidManifest.xml" "$PKG" <<'PY'
import sys
src, dst, pkg = sys.argv[1:4]
xml = open(src, encoding="utf-8").read()
xml = xml.replace("<manifest ", '<manifest package="%s" ' % pkg, 1)
open(dst, "w", encoding="utf-8").write(xml)
PY

echo "==> 2/6 aapt2 link"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BUILD/manifest/AndroidManifest.xml" \
  -A "$APP/assets" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --java "$BUILD/gen" \
  --auto-add-overlay \
  "$BUILD/res/resources.zip"

echo "==> 3/6 ecj (R class)"
find "$BUILD/gen" -name '*.java' > "$BUILD/rsources.txt"
"$JAVA" -jar "$ECJ_JAR" -encoding UTF-8 -source 17 -target 17 -nowarn \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" @"$BUILD/rsources.txt"

echo "==> 4/6 kotlinc"
find "$APP/java" -name '*.kt' > "$BUILD/sources.txt"
PATH="$JAVA_HOME/bin:$PATH" "$KOTLINC_BIN" \
  -jvm-target 1.8 -no-reflect \
  -classpath "$ANDROID_JAR:$BUILD/classes" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "==> 5/6 d8"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
D8_EXTRA=()
# Kotlin may need the jdk7/jdk8 stdlib extensions and annotations at runtime on older APIs.
for extra in kotlin-stdlib-jdk7 kotlin-stdlib-jdk8 annotations-13.0; do
  if [ -f "$KOTLINC_HOME/lib/$extra.jar" ]; then
    D8_EXTRA+=("$KOTLINC_HOME/lib/$extra.jar")
  fi
done
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" @"$BUILD/classes.txt" "$KOTLINC_HOME/lib/kotlin-stdlib.jar" "${D8_EXTRA[@]}"

# aapt2 link produced a resources-only APK; splice the dex in with plain zip writes.
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/dex/app.apk" <<'PY'
import shutil, sys, zipfile
base, dex, out = sys.argv[1:4]
shutil.copyfile(base, out)
with zipfile.ZipFile(out, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('   packaged', out)
PY

echo "==> 6/6 sign ($TYPE)"
SIGN_ARGS=()
# Release: uses release.keystore + keystore.properties at the repo root when present
# (both git-ignored; see README "Signing"). Otherwise falls back to the debug key.
if [ "$TYPE" = "release" ] && [ -f "$ROOT/release.keystore" ] && [ -f "$ROOT/keystore.properties" ]; then
  PBB_KEYSTORE="$ROOT/release.keystore"
  PBB_KEYSTORE_PASS="$(sed -n 's/^storePassword=//p' "$ROOT/keystore.properties")"
  PBB_KEY_ALIAS="$(sed -n 's/^keyAlias=//p' "$ROOT/keystore.properties")"
  PBB_KEY_PASS="$(sed -n 's/^keyPassword=//p' "$ROOT/keystore.properties")"
  SIGN_ARGS+=(--ks "$PBB_KEYSTORE" --ks-pass "pass:${PBB_KEYSTORE_PASS:?}" \
             --ks-key-alias "${PBB_KEY_ALIAS:?}" --key-pass "pass:${PBB_KEY_PASS:-$PBB_KEYSTORE_PASS}" \
             "$BUILD/dex/app.apk")
else
  KS="$ROOT/buildtools/debug.keystore"
  if [ ! -f "$KS" ]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias pbblive -keyalg RSA -keysize 2048 -validity 10950 \
      -dname "CN=PBB LIVE Debug, O=PBB LIVE" >/dev/null 2>&1
  fi
  SIGN_ARGS+=(--ks "$KS" --ks-pass pass:android --ks-key-alias pbblive --key-pass pass:android \
             "$BUILD/dex/app.apk")
fi

"$JAVA" -jar "$APKSIGNER_JAR" sign \
  --min-sdk-version "$MIN_SDK" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  "${SIGN_ARGS[@]}"

mkdir -p "$(dirname "$OUT")"
cp "$BUILD/dex/app.apk" "$OUT"

echo
echo "APK: $OUT"
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$OUT" | sed -n '1,4p'
ls -lh "$OUT"
