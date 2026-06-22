#!/usr/bin/env bash
#
# Generate a release signing keystore for RetAlert's Android APK.
#
# WHAT IS A KEYSTORE? Android requires every app to be cryptographically signed.
# The keystore is a file holding your private signing key (protected by
# passwords). The SAME key must sign every future update of the app, so keep
# this file and its passwords safe and secret: anyone who has them can publish
# updates pretending to be you, and losing them means you can never update the
# app under the same identity again.
#
# Run this ONCE. Do NOT commit the resulting file (.gitignore already blocks
# *.keystore). Then add the GitHub secrets it prints so CI can sign releases.
#
# Usage: scripts/make-keystore.sh [output.keystore] [alias]
set -euo pipefail

OUT="${1:-retalert-release.keystore}"
ALIAS="${2:-retalert}"

if ! command -v keytool >/dev/null 2>&1; then
  echo "error: 'keytool' not found — install a JDK (e.g. apt install openjdk-17-jdk)." >&2
  exit 1
fi

if [ -e "$OUT" ]; then
  echo "error: $OUT already exists; refusing to overwrite." >&2
  exit 1
fi

echo "Creating release keystore '$OUT' (alias '$ALIAS')."
echo "You'll be prompted for a keystore password, a key password, and your name/org."
keytool -genkeypair -v \
  -keystore "$OUT" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000

echo
echo "Done. Add these GitHub repo secrets (Settings -> Secrets -> Actions):"
echo "  ANDROID_KEYSTORE_BASE64    -> output of:  base64 -w0 \"$OUT\""
echo "  ANDROID_KEYSTORE_PASSWORD  -> the keystore password you just set"
echo "  ANDROID_KEY_ALIAS          -> $ALIAS"
echo "  ANDROID_KEY_PASSWORD       -> the key password you just set"
echo
echo "Then a 'git tag vX.Y.Z && git push --tags' produces a SIGNED release APK."
echo "Keep '$OUT' backed up somewhere safe and private."
