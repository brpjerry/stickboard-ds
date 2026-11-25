# Releasing

Two GitHub Actions workflows build the app:

- **CI** (`.github/workflows/ci.yml`) runs on every push to `main` and every pull request: unit
  tests, lint for both build types and a debug build. Test and lint reports and the debug APK are
  kept as workflow artifacts. Its `build` job is a required check for merging into `main`.
- **Release** (`.github/workflows/release.yml`) runs when a tag starting with `v` is pushed. It
  runs CI first, checks that the tag matches the app's `versionName`, builds the release APK signed
  with the release key, checks that the signature is that key's, and publishes a GitHub release
  with the APK, a `SHA256SUMS` file and generated notes.

`main` only takes changes through pull requests, merged by squashing, once CI passes.

## Cutting a release

1. In `app/build.gradle.kts`, raise `versionCode` by one and set `versionName` to the new version,
   for example `0.2.0`. Android only installs an update with a higher `versionCode`.
2. Merge that into `main` through a pull request.
3. Tag the merged commit on `main` and push the tag:

   ```sh
   git pull
   git tag v0.2.0
   git push origin v0.2.0
   ```

The Release workflow does the rest. If it fails before publishing, fix the problem, then move the
tag to the fixed commit (`git tag -f v0.2.0 && git push -f origin v0.2.0`). Once a release is
published, ship fixes as a new version instead.

## The release key

Release APKs are signed with one key for the lifetime of the app: Android refuses to update an
installed copy with an APK signed by a different key, so losing the key means every user has to
uninstall (and lose their settings) to get later versions.

- The keystore is a PKCS12 file with one key, alias `stickboard-ds`, RSA 4096, valid until 2054.
  Its certificate's SHA-256 fingerprint is
  `4F:79:BE:6F:65:23:19:55:FE:B6:61:CD:95:8A:33:F4:48:99:35:01:EB:7F:64:A7:EE:3A:49:EB:85:91:D1:6D`.
  The Release workflow refuses to publish an APK signed with anything else.
- The repository's Actions secrets hold it: `RELEASE_KEYSTORE_BASE64` (the keystore, base64) and
  `RELEASE_KEYSTORE_PASSWORD` (its password, which is also the key's). Secrets cannot be read back
  out of GitHub, so keep the original files backed up somewhere safe, such as a password manager.
- Never commit the keystore; `.gitignore` excludes `*.jks`, `*.keystore` and `*.p12`.

To sign a release build locally, point Gradle at the keystore through the environment:

```sh
export RELEASE_KEYSTORE=/path/to/stickboard-ds-release.jks
export RELEASE_KEY_ALIAS=stickboard-ds
read -rs RELEASE_KEYSTORE_PASSWORD && export RELEASE_KEYSTORE_PASSWORD
export RELEASE_KEY_PASSWORD="$RELEASE_KEYSTORE_PASSWORD"
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

A release-signed APK cannot replace a debug build on a device, or the other way round, because
their keys differ: switching between them means uninstalling first, which clears the settings.
