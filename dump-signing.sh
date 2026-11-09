#!/usr/bin/env bash
# One-off: extract the upgrade-compatible signing key from the existing APK and write
# keystore.properties for Gradle. Never commit the resulting keystore or properties file.
set -euo pipefail
APK="${1:-pbbforfree.apk}"
OUT="release.keystore"
alias="release"
pass="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"

[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }

rm -f pbbforfree.p12
keytool -importkeystore \
  -srckeystore "$APK" -srcstoretype PKCS12 -srcstorepass "" \
  -destkeystore pbbforfree.p12 -deststoretype PKCS12 -deststorepass changeit \
  -srcalias "$(keytool -list -keystore "$APK" -storepass "" 2>/dev/null | awk -F, '/PrivateKeyEntry/{print $1; exit}')" \
  -destalias "$alias"
rm -f "$OUT"
keytool -importkeystore -srckeystore pbbforfree.p12 -srcstoretype PKCS12 -srcstorepass changeit \
  -destkeystore "$OUT" -deststoretype JKS -deststorepass "$pass" \
  -srcalias "$alias" -destalias "$alias" -srckeypass changeit -destkeypass "$pass" -noprompt
rm -f pbbforfree.p12

cat > keystore.properties <<EOF
storeFile=$OUT
storePassword=$pass
keyAlias=$alias
keyPassword=$pass
EOF

echo "Wrote $OUT and keystore.properties (both git-ignored). Back them up: losing this key"
echo "means future builds can no longer be installed as updates over the old APK."
