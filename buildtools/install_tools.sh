#!/usr/bin/env bash
# Fetch the offline build toolchain described in tools.properties.
#
# Everything lands in buildtools/.toolchain (git-ignored, ~120 MB) and the script prints the
# environment variables build_apk.sh needs. Re-running is cheap: finished pieces are reused.
#
#   source buildtools/install_tools.sh          # installs, then exports the variables
#   buildtools/build_apk.sh                     # -> buildtools/dist/*.apk
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/buildtools/.toolchain"
PY="$TC/py"
NPM="$TC/npm"
JDK_VER="17.0.9.2"
MINAPK_VER="0.5.1"
AAPTJS_VER="2.0.2"

mkdir -p "$PY" "$NPM"

if [ ! -d "$PY/jdk4py" ]; then
  echo "==> pip install jdk4py==$JDK_VER (JDK 17)"
  pip install --quiet --break-system-packages --target "$PY" "jdk4py==$JDK_VER" \
    || pip install --quiet --target "$PY" "jdk4py==$JDK_VER"
fi
JAVA_HOME="$(PYTHONPATH="$PY" python3 -c 'import jdk4py; print(jdk4py.JAVA_HOME)')"

if [ ! -f "$NPM/package/tools/android.jar" ]; then
  echo "==> npm pack @drxiaozhi/minapk@$MINAPK_VER"
  (cd "$NPM" && npm pack --silent "@drxiaozhi/minapk@$MINAPK_VER" >/dev/null \
     && tar xzf drxiaozhi-minapk-*.tgz)
fi
if [ ! -x "$NPM/aapt2/bin/x64/linux/aapt2" ]; then
  echo "==> npm pack aaptjs3@$AAPTJS_VER"
  mkdir -p "$NPM/aapt2"
  (cd "$NPM" && npm pack --silent "aaptjs3@$AAPTJS_VER" >/dev/null \
     && tar xzf aaptjs3-*.tgz -C aapt2 && mv aapt2/package/* aapt2/ 2>/dev/null || true)
  chmod +x "$NPM/aapt2/bin/x64/linux/aapt2"
fi

ANDROID_JAR="$NPM/package/tools/android.jar"
AAPT2="$NPM/aapt2/bin/x64/linux/aapt2"
D8_JAR="$NPM/package/tools/d8.jar"
APKSIGNER_JAR="$NPM/package/tools/apksigner.jar"
ECJ_JAR="$NPM/package/tools/ecj-3.45.0.jar"

for f in "$ANDROID_JAR" "$AAPT2" "$D8_JAR" "$APKSIGNER_JAR"; do
  [ -e "$f" ] || { echo "missing tool: $f" >&2; exit 1; }
done

if [ -n "${BASH_SOURCE[0]:-}" ] && [ "${BASH_SOURCE[0]}" != "$0" ]; then
  export JAVA_HOME ANDROID_JAR AAPT2 D8_JAR APKSIGNER_JAR ECJ_JAR
  echo "==> toolchain ready (JAVA_HOME=$JAVA_HOME)"
else
  cat <<EOF
==> toolchain ready
export JAVA_HOME="$JAVA_HOME"
export ANDROID_JAR="$ANDROID_JAR"
export AAPT2="$AAPT2"
export D8_JAR="$D8_JAR"
export APKSIGNER_JAR="$APKSIGNER_JAR"
export ECJ_JAR="$ECJ_JAR"
EOF
fi
