#!/usr/bin/env bash
# Show what a signing key is, without printing any password.
#
#   ./dump-signing.sh release.keystore            # certificate DNs + fingerprints
#   ./dump-signing.sh pbbforfree.apk              # signer of an existing APK
#
# Uses the dev toolchain (source buildtools/install_tools.sh first) or system keytool/apksigner.
set -euo pipefail

TARGET="${1:?usage: $0 <keystore|.apk>}"
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
JAVA="$JAVA_HOME/bin/java"

case "$TARGET" in
  *.apk)
    if [ -n "${APKSIGNER_JAR:-}" ]; then
      "$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs -v "$TARGET" | sed -n '1,12p'
    else
      apksigner verify --print-certs "$TARGET" | sed -n '1,12p'
    fi
    ;;
  *)
    : "${KEYSTORE_PASS:?set KEYSTORE_PASS in the environment (never on the command line)}"
    "$JAVA_HOME/bin/keytool" -list -v -keystore "$TARGET" -storepass "$KEYSTORE_PASS" \
      | grep -E "Alias|Owner|Issuer|Valid|SHA256|Signature algorithm" | head -20
    ;;
esac
