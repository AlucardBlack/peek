# Contributing guidelines

## Tooling

IDE: We use Android Studio, download it here: http://developer.android.com/tools/studio/

ADB Idea: A useful plugin for working with android apps: https://github.com/pbreault/adb-idea


## Incrementing the patch number

Before each release be sure to increment the versionPatch number within build.gradle. This number should be reset to zero after a minor or major version bump (typically during a release). The patch number is reported to crashlytics and can help pinpoint what commits caused a crash, and to the beta community.

## Release checklist

1. Confirm CI is green on `main` for the commit you're releasing (both the
   `unit-tests` and `build-apk` jobs in `.github/workflows/ci.yml`).
2. Bump `versionPatch` (or `versionMinor`/`versionMajor`, resetting
   `versionPatch` to 0) in `Application/Peek/build.gradle`, per the section
   above.
3. One-time setup, if you haven't released from this machine before: copy
   `build-release.sh.template` to `build-release.sh` (gitignored - it holds
   secrets) and fill in the four `PEEK_KEYSTORE_*`/`PEEK_KEY_*` values for
   your release keystore.
4. Run `./build-release.sh` from the repo root. It assembles
   `assemblePlaystoreRelease` and copies the signed APK to `dist/`.
5. Sanity-check the output: `dist/Peek-playstore-release.apk` exists, and its
   version (`aapt dump badging dist/Peek-playstore-release.apk | grep versionName`)
   matches what you bumped to in step 2.
6. Install the release APK on a device and smoke-test the core flow (open a
   link into a bubble, expand it, close it) before uploading - the release
   build type runs R8/minification, which debug builds don't, so this is the
   first point a shrinking-related regression could show up.
7. Upload `dist/Peek-playstore-release.apk` to the Play Console release track
   of your choice.
8. Commit the `versionPatch`/`versionMinor`/`versionMajor` bump from step 2
   and tag the release commit.
