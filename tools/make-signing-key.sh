#!/usr/bin/env bash
# Makes the private signing key for the builds handed to friends (2.0), on YOUR computer, and prints the four values
# to paste into the repository's secrets. Nothing is sent anywhere; the key file is written outside the repository
# (and *.jks is ignored by git in any case). Needs `keytool`, which comes with any Java (JDK) and with Android Studio.
#
#   bash tools/make-signing-key.sh            # asks for a password
#
# Keep the file it writes (~/couch-release.jks) and the password somewhere safe: every later release for friends must
# be signed with the same key, or their phones and TVs will refuse the update.
set -euo pipefail

out="${COUCH_KEY_OUT:-$HOME/couch-release.jks}"
alias_name="couch"

command -v keytool > /dev/null || { echo "keytool was not found. Install a JDK (or use Android Studio's), then run this again." >&2; exit 1; }
if [ -e "$out" ]; then echo "$out already exists. Move it away first if you really want a new key (a new key cannot update what the old one signed)." >&2; exit 1; fi

if [ -z "${COUCH_KEY_PASSWORD:-}" ]; then
  read -r -s -p "A password for the key (at least 6 characters; you will paste it into GitHub once): " COUCH_KEY_PASSWORD; echo
fi
[ "${#COUCH_KEY_PASSWORD}" -ge 6 ] || { echo "The password must be at least 6 characters." >&2; exit 1; }

keytool -genkeypair -v -keystore "$out" -storetype PKCS12 -alias "$alias_name" \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass "$COUCH_KEY_PASSWORD" -keypass "$COUCH_KEY_PASSWORD" \
  -dname "CN=Al HaSapa" > /dev/null 2>&1

b64="$(base64 < "$out" | tr -d '\n')"
cat <<TXT

The key was written to: $out   (keep this file and its password; do NOT put it in the repository)

Now add four secrets at:
  https://github.com/stevenbarzilay-cloud/Al-hasapa/settings/secrets/actions   ("New repository secret")

  Name: COUCH_KEYSTORE_PASSWORD   Value: the password you just chose
  Name: COUCH_KEY_PASSWORD        Value: the same password
  Name: COUCH_KEY_ALIAS           Value: $alias_name
  Name: COUCH_KEYSTORE_B64        Value: the one long line below (all of it)

$b64

Then: Actions -> "Release for friends" -> Run workflow.
TXT
