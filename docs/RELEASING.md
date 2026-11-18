# Releasing

Releases are built and published by GitHub Actions ([`.github/workflows/release.yml`](../.github/workflows/release.yml)) when a version tag is pushed.

## Cutting a release

```bash
git checkout main && git pull
git tag v1.2.3            # semantic version, always increasing
git push origin v1.2.3
```

The workflow then:

1. Derives `versionName` 1.2.3 and `versionCode` 10203 (major × 10000 + minor × 100 + patch) from the tag.
2. Runs the unit tests and builds the release APK with R8 shrinking, signed with the release key.
3. Verifies the signature, and writes a SHA-256 checksum.
4. Creates the GitHub release with `Blockwork-1.2.3.apk`, the `.sha256` file, the certificate fingerprint and generated release notes.

Every push to `main` and every pull request runs [`ci.yml`](../.github/workflows/ci.yml): unit tests, lint and a debug APK, which is uploaded as a workflow artifact.

## Signing key

The APK is signed with a release key that is stored as **repository secrets**:

| Secret | Content |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias (`indoortrain`) |
| `SIGNING_KEY_PASSWORD` | key password |

The maintainer keeps the original keystore and passwords outside the repository, in `~/.android-keys/indoortrain-release.*`, and must **back them up** (password manager / offline copy). If the key is lost, users can no longer update. They would have to uninstall, and lose their data, to install a build signed with a new key.

Never commit the keystore or passwords.

### Local release build

```bash
set -a; . ~/.android-keys/indoortrain-release.properties; set +a
SIGNING_STORE_FILE="$storeFile" SIGNING_STORE_PASSWORD="$storePassword" \
SIGNING_KEY_ALIAS="$keyAlias" SIGNING_KEY_PASSWORD="$keyPassword" \
./gradlew assembleRelease -PappVersionName=1.2.3 -PappVersionCode=10203
```

Without the `SIGNING_*` variables, `assembleRelease` produces an unsigned APK that can't be installed.

## Before tagging

- Unit tests are green (`./gradlew test`).
- The R8-shrunk release build has been smoke-tested on a device. Shrinking can break code that libraries reach only through reflection.
- The database schema: if entities changed, a new version with a migration exists, and `data/schemas/` is committed.
