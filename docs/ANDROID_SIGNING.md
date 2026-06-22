# Android release signing (keystore)

## What is a keystore, in plain terms?

Android won't install an app unless it's **signed**. Signing proves the app
came from you and hasn't been tampered with.

- **Debug APK** (what CI builds on every push): signed automatically with a
  throwaway debug key. Fine for testing/sideloading, not for distribution.
- **Release APK** (built on a version tag): should be signed with **your own**
  key, stored in a **keystore** — a small file holding a private key, locked
  with passwords.

Two things to remember:

1. **Keep it secret and safe.** Anyone with your keystore + passwords can ship
   updates pretending to be you.
2. **Keep the same one forever.** Every future update must be signed with the
   *same* key, or users can't update over an existing install. Back it up.

The keystore is a private key, so it is **never committed** (`.gitignore`
blocks `*.keystore` / `*.jks`).

## One-time setup

1. Create your keystore (needs a JDK for `keytool`):

   ```sh
   scripts/make-keystore.sh
   ```

   It asks for a keystore password, a key password, and your name/org, then
   writes `retalert-release.keystore`.

2. Add four GitHub repo secrets (**Settings → Secrets and variables →
   Actions → New repository secret**):

   | Secret | Value |
   |--------|-------|
   | `ANDROID_KEYSTORE_BASE64` | `base64 -w0 retalert-release.keystore` (paste the output) |
   | `ANDROID_KEYSTORE_PASSWORD` | the keystore password you chose |
   | `ANDROID_KEY_ALIAS` | `retalert` (the alias) |
   | `ANDROID_KEY_PASSWORD` | the key password you chose |

   Or add them from the terminal with the GitHub CLI (no web UI, no pasting the
   base64 by hand):

   ```sh
   gh secret set ANDROID_KEYSTORE_BASE64 < <(base64 -w0 retalert-release.keystore)
   gh secret set ANDROID_KEY_ALIAS       --body retalert
   gh secret set ANDROID_KEYSTORE_PASSWORD   # prompts for the value
   gh secret set ANDROID_KEY_PASSWORD        # prompts for the value
   ```

   Verify they registered: `gh secret list` (shows names only, never values).

## Cutting a signed release

```sh
git tag v0.1.0
git push --tags
```

The `Release` workflow then builds the APK, signs it with your keystore
(decoded from the secret), and attaches it — plus the Python wheel and the
Linux desktop binary — to a new GitHub Release.

If the secrets aren't set, the release still runs but the APK is **unsigned**
(CI prints a warning); add the secrets to get a signed one.
