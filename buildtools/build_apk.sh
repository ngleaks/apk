#!/usr/bin/env bash
# Offline APK build: aapt2 -> javac -> d8 -> apksigner.
#
# No Gradle, no Maven, no Android SDK download. The four steps Gradle would run are spelled out, so
# the build works on a host that can only reach npmjs/pypi. Tools come from buildtools/install_tools.sh
# (see tools.properties).
#
#   source buildtools/install_tools.sh     # once: fetches the toolchain
#   buildtools/build_apk.sh                # release APK -> buildtools/dist/
#   buildtools/build_apk.sh --debug        # debug APK
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/app/src/main"
BUILD="$ROOT/buildtools/build"
TYPE="release"
OUT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --debug) TYPE="debug"; shift ;;
    --release) TYPE="release"; shift ;;
    --out) OUT="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

: "${JAVA_HOME:?run: source buildtools/install_tools.sh}"
: "${ANDROID_JAR:?run: source buildtools/install_tools.sh}"
: "${AAPT2:?run: source buildtools/install_tools.sh}"
: "${D8_JAR:?run: source buildtools/install_tools.sh}"
: "${APKSIGNER_JAR:?run: source buildtools/install_tools.sh}"

JAVA="$JAVA_HOME/bin/java"
# Full JDKs have javac; the CI/sandbox JDK is a JRE, so fall back to the Eclipse compiler.
if [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC_CMD=("$JAVA_HOME/bin/javac")
else
  : "${ECJ_JAR:?set ECJ_JAR (this JDK has no javac)}"
  JAVAC_CMD=("$JAVA" -jar "$ECJ_JAR")
fi

MIN_SDK=24
TARGET_SDK=35
VERSION_CODE="${VERSION_CODE:-10}"
VERSION_NAME="${VERSION_NAME:-2.0.0}"
PKG="${PKG:-com.pbb.livetv}"
SUFFIX=""
[ "$TYPE" = "debug" ] && SUFFIX=".debug"
OUT="${OUT:-$ROOT/buildtools/dist/pbb-live-tv-${VERSION_NAME}-${TYPE}.apk}"

rm -rf "$BUILD"
mkdir -p "$BUILD"/{res,gen,classes,dex,dist}

echo "==> 1/6 aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$BUILD/res/resources.zip"

# aapt2 requires package=""; AGP forbids it in the source manifest, so inject it into a copy.
mkdir -p "$BUILD/manifest"
python3 - "$APP/AndroidManifest.xml" "$BUILD/manifest/AndroidManifest.xml" "$PKG$SUFFIX" <<'PY'
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
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --java "$BUILD/gen" \
  --custom-package "$PKG" \
  --auto-add-overlay \
  -A "$APP/assets" \
  "$BUILD/res/resources.zip"

echo "==> 3/6 javac"
find "$APP/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
# Boot class path is android.jar only; jrt-fs.jar (JDK 9+ replacement for rt.jar) is on the class
# path so lambda plumbing resolves without letting java.* shadow android.*.
"${JAVAC_CMD[@]}" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR:$JAVA_HOME/lib/jrt-fs.jar" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "==> 4/6 d8"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" @"$BUILD/classes.txt"

# aapt2 link produced a resources(+assets)-only APK; splice the dex in with plain zip writes.
echo "==> 5/6 package"
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/app.apk" <<'PY'
import shutil, sys, zipfile
base, dex, out = sys.argv[1:4]
shutil.copyfile(base, out)
with zipfile.ZipFile(out, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY

echo "==> 6/6 sign ($TYPE)"
SIGN_ARGS=()
if [ "$TYPE" = "release" ] && [ -f "$ROOT/release.keystore" ] && [ -f "$ROOT/keystore.properties" ]; then
  PBB_KEYSTORE="$ROOT/release.keystore"
  PBB_KEYSTORE_PASS="$(sed -n 's/^storePassword=//p' "$ROOT/keystore.properties")"
  PBB_KEY_ALIAS="$(sed -n 's/^keyAlias=//p' "$ROOT/keystore.properties")"
  PBB_KEY_PASS="$(sed -n 's/^keyPassword=//p' "$ROOT/keystore.properties")"
fi

if [ "$TYPE" = "release" ] && [ -n "${PBB_KEYSTORE:-}" ]; then
  SIGN_ARGS+=(--ks "$PBB_KEYSTORE" --ks-pass "pass:${PBB_KEYSTORE_PASS:?}" \
             --ks-key-alias "${PBB_KEY_ALIAS:?}" --key-pass "pass:${PBB_KEY_PASS:-$PBB_KEYSTORE_PASS}")
  echo "    signing with release.keystore (alias ${PBB_KEY_ALIAS})"
else
  KS="$ROOT/buildtools/debug.keystore"
  if [ ! -f "$KS" ]; then
    # A throwaway local key: it is git-ignored and only exists so a CI build is installable.
    if [ -x "$JAVA_HOME/bin/keytool" ]; then
      KEYTOOL="$JAVA_HOME/bin/keytool"
    else
      : "${KEYTOOL_JAR:?set KEYTOOL_JAR (no bin/keytool in this JDK)}"
      KEYTOOL=("$JAVA" -jar "$KEYTOOL_JAR")
    fi
    "${KEYTOOL[@]}" -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias pbbtv -keyalg RSA -keysize 2048 -validity 10950 \
      -dname "CN=PBB Live TV Debug, O=PBB Live" >/dev/null
  fi
  SIGN_ARGS+=(--ks "$KS" --ks-pass pass:android --ks-key-alias pbbtv --key-pass pass:android)
  echo "    signing with the local debug key (buildtools/debug.keystore)"
fi

"$JAVA" -jar "$APKSIGNER_JAR" sign \
  --min-sdk-version "$MIN_SDK" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  "${SIGN_ARGS[@]}" "$BUILD/app.apk"

mkdir -p "$(dirname "$OUT")"
cp "$BUILD/app.apk" "$OUT"

echo
echo "APK: $OUT"
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$OUT" | sed -n '1,4p'
ls -lh "$OUT"
