#!/usr/bin/env bash
# Create your own release signing key WITHOUT committing any secret.
#
#   buildtools/make_keystore.sh
#
# Writes release.keystore + keystore.properties (both git-ignored) and prints the verification
# command. Passwords are read interactively and never echoed; you can also provide them with
#   KEYSTORE_PASS=... KEY_PASS=... buildtools/make_keystore.sh
# for CI, where the values should come from the CI secret store.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KS="$ROOT/release.keystore"
PROPS="$ROOT/keystore.properties"
ALIAS="${KEY_ALIAS:-pbbtv}"

if [ -f "$KS" ]; then
  echo "$KS already exists - refusing to overwrite a signing key." >&2
  echo "Delete it deliberately if you really want a new one." >&2
  exit 1
fi

: "${JAVA_HOME:?run: source buildtools/install_tools.sh first}"
KEYTOOL="$JAVA_HOME/bin/keytool"
[ -x "$KEYTOOL" ] || { echo "no keytool in $JAVA_HOME/bin" >&2; exit 1; }

read -r -p "Your name/organisation (CN=...): " CN
read -r -p "Validity in days [10000]: " DAYS
DAYS="${DAYS:-10000}"

if [ -n "${KEYSTORE_PASS:-}" ]; then
  STORE_PASS="$KEYSTORE_PASS"
else
  read -r -s -p "Keystore password: " STORE_PASS; echo
  read -r -s -p "Repeat: " STORE_PASS2; echo
  [ "$STORE_PASS" = "$STORE_PASS2" ] || { echo "passwords differ" >&2; exit 1; }
fi
KEY_PASS="${KEY_PASS:-$STORE_PASS}"

"$KEYTOOL" -genkeypair -v -keystore "$KS" -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity "$DAYS" \
  -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
  -dname "CN=${CN:-PBB Live}, O=${CN:-PBB Live}"

umask 077
cat > "$PROPS" <<EOF
# Local signing configuration - NEVER commit this file (see .gitignore).
storeFile=release.keystore
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$KEY_PASS
EOF
chmod 600 "$KS" "$PROPS"

echo
echo "Created: $KS and $PROPS (mode 600, git-ignored)"
echo "Back up both files somewhere safe - losing the key means you cannot update the app."
echo
echo "Next: buildtools/build_apk.sh    (or ./gradlew :app:assembleRelease)"
echo "Verify: apksigner verify --print-certs buildtools/dist/pbb-live-tv-2.0.0-release.apk"
