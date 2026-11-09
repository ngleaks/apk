#!/usr/bin/env bash
# Offline APK build: aapt2 -> javac -> d8 -> apksigner.
#
# No Gradle, no Maven, no Android SDK download. The same four steps Gradle runs are spelled out so
# the build works in a sandbox that can only reach github.com / npmjs / pypi. Tools come from
# tools.properties (see README): JDK 17, aapt2, d8, apksigner and android.jar.
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

: "${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
: "${ANDROID_JAR:?set ANDROID_JAR to platform android.jar}"
: "${AAPT2:?set AAPT2 to the aapt2 binary}"
: "${D8_JAR:?set D8_JAR to d8.jar}"
: "${APKSIGNER_JAR:?set APKSIGNER_JAR to apksigner.jar}"

JAVA="$JAVA_HOME/bin/java"
KEYTOOL_JAR="${KEYTOOL_JAR:-$JAVA_HOME/lib/keytool.jar}"
# A full JDK has javac; the CI/sandbox JDK is a JRE, so fall back to the Eclipse compiler jar.
if [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC_CMD=("$JAVA_HOME/bin/javac")
else
  : "${ECJ_JAR:?set ECJ_JAR to ecj.jar (used because this JDK has no javac)}"
  JAVAC_CMD=("$JAVA" -jar "$ECJ_JAR")
fi

MIN_SDK=24
TARGET_SDK=35
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"
PKG="com.pbb.streamtv"
OUT="${OUT:-$ROOT/buildtools/dist/pbb-tv-${VERSION_NAME}-${TYPE}.apk}"

rm -rf "$BUILD"
mkdir -p "$BUILD"/{res,gen,classes,dex,dist}

echo "==> 1/5 aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$BUILD/res/resources.zip"

# aapt2 requires package=""; AGP forbids it in the source manifest, so inject it into a copy.
mkdir -p "$BUILD/manifest"
python3 - "$APP/AndroidManifest.xml" "$BUILD/manifest/AndroidManifest.xml" "$PKG" <<'PY'
import sys
src, dst, pkg = sys.argv[1:4]
xml = open(src, encoding="utf-8").read()
xml = xml.replace("<manifest ", '<manifest package="%s" ' % pkg, 1)
open(dst, "w", encoding="utf-8").write(xml)
PY

echo "==> 2/5 aapt2 link"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BUILD/manifest/AndroidManifest.xml" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --java "$BUILD/gen" \
  --auto-add-overlay \
  "$BUILD/res/resources.zip"

echo "==> 3/5 javac"
find "$APP/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
# android.jar only, but tell the compiler the JDK is available for lambda plumbing.
# (Passing rt.jar on -bootclasspath would make the compiler pick java.* from the JDK instead.)
# jrt-fs.jar is the JDK 9+ "rt.jar": it satisfies lambda plumbing without shadowing android.*
"${JAVAC_CMD[@]}" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR:$JAVA_HOME/lib/jrt-fs.jar" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "==> 4/5 d8"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" @"$BUILD/classes.txt"

# aapt2 link produced a resources-only APK; splice the dex in with plain zip writes.
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/dex/app.apk" <<'PY'
import shutil, sys, zipfile
base, dex, out = sys.argv[1:4]
shutil.copyfile(base, out)
with zipfile.ZipFile(out, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('   packaged', out)
PY

echo "==> 5/5 sign ($TYPE)"
SIGN_ARGS=()
# Optional: reuse the key the old pbbforfree.apk was signed with (see dump-signing.sh) so this
# build installs as an update. Otherwise a local debug key is generated once and reused.
if [ -f "$ROOT/release.keystore" ] && [ -f "$ROOT/keystore.properties" ]; then
  PBB_KEYSTORE="$ROOT/release.keystore"
  PBB_KEYSTORE_PASS="$(sed -n 's/^storePassword=//p' "$ROOT/keystore.properties")"
  PBB_KEY_ALIAS="$(sed -n 's/^keyAlias=//p' "$ROOT/keystore.properties")"
  PBB_KEY_PASS="$(sed -n 's/^keyPassword=//p' "$ROOT/keystore.properties")"
fi

if [ "$TYPE" = "release" ] && [ -n "${PBB_KEYSTORE:-}" ]; then
  SIGN_ARGS+=(--ks "$PBB_KEYSTORE" --ks-pass "pass:${PBB_KEYSTORE_PASS:?}" \
             --ks-key-alias "${PBB_KEY_ALIAS:?}" --key-pass "pass:${PBB_KEY_PASS:-$PBB_KEYSTORE_PASS}" \
             "$BUILD/dex/app.apk")
else
  KS="$ROOT/buildtools/debug.keystore"
  if [ ! -f "$KS" ]; then
    "$JAVA" -jar "$KEYTOOL_JAR" -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias pbbtv -keyalg RSA -keysize 2048 -validity 10950 \
      -dname "CN=PBBforFREE TV Debug, O=PBBforFREE" >/dev/null 2>&1
  fi
  SIGN_ARGS+=(--ks "$KS" --ks-pass pass:android --ks-key-alias pbbtv --key-pass pass:android \
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
