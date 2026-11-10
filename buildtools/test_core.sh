#!/usr/bin/env bash
# Compile and run the pure-logic checks (buildtools/VerifyCore.java) on a plain JVM.
# No device, no JUnit, no Gradle: the same sources that go into the APK are compiled against
# android.jar with the Eclipse compiler and executed with the java launcher.
#
#   source buildtools/install_tools.sh && buildtools/test_core.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/src/main/java"
OUT="$ROOT/buildtools/build/test"

: "${JAVA_HOME:?run: source buildtools/install_tools.sh}"
: "${ANDROID_JAR:?run: source buildtools/install_tools.sh}"
: "${ECJ_JAR:?run: source buildtools/install_tools.sh}"

JAVA="$JAVA_HOME/bin/java"
rm -rf "$OUT"
mkdir -p "$OUT"

# Pure classes only: no android.* imports, so they run on a normal JVM.
SOURCES=(
  "$SRC/com/pbb/livetv/Camera.java"
  "$SRC/com/pbb/livetv/StreamFormat.java"
  "$SRC/com/pbb/livetv/core/GridNavigator.java"
  "$SRC/com/pbb/livetv/media/HlsPlaylist.java"
  "$SRC/com/pbb/livetv/ui/TileLayout.java"
  "$ROOT/buildtools/VerifyCore.java"
)

echo "==> compiling ${#SOURCES[@]} sources"
# android.jar first (android.* API) plus jrt-fs.jar so java.lang.invoke (lambdas) resolves.
"$JAVA" -jar "$ECJ_JAR" -source 1.8 -target 1.8 -nowarn \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR:$JAVA_HOME/lib/jrt-fs.jar" -d "$OUT" "${SOURCES[@]}"

echo "==> running checks"
"$JAVA" -cp "$OUT" VerifyCore
