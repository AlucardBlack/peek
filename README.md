# Peek

Peek is an Android browser that opens links in a small floating "bubble"
instead of a full-screen tab, so you can keep reading without losing your
place in whatever app you were in.

Peek is a modernized fork of [Link Bubble](https://github.com/AlucardBlack/link-bubble)
(originally by Chris Lacy, later maintained by Brave Software as
`brave/browser-android`). The codebase has since been fully migrated to
Kotlin/AndroidX, restyled with Material3 and dynamic color, and renamed
throughout — see [LICENSE.txt](LICENSE.txt) for the license (MPL 2.0)
carried over from the original project.

## Install instructions and setup

`git clone git@github.com:AlucardBlack/peek.git`

Copy `Application/LinkBubble/src/main/AndroidManifest.xml.template` to
`Application/LinkBubble/src/main/AndroidManifest.xml`.

`npm install`

## Building

Open `./Application/` in Android Studio and build. You'll need the NDK
installed if you don't already have it, instructions below.

## Building release build

Copy `build-release.sh.template` to `build-release.sh`.

Modify each of these exported environment variables: `PEEK_KEYSTORE_LOCATION`,
`PEEK_KEYSTORE_PASSWORD`, `PEEK_KEY_ALIAS`, `PEEK_KEY_PASSWORD`.

If you get an error similar to:

> Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES]

Try uninstalling the application which already exists on your plugged in device.

## Installing the NDK

Android Studio has an easy way to download and link to the NDK.

In the menu navigate to File, Project Structure. Click the 'Download Android NDK' link. This should download and unzip the NDK, as well as link it inside of local.properties.

## ADB

If you don't have `adb` in your path add it to your `~/.bash_profile` or similar file:

`export PATH=/Users/<your-username>/Library/Android/sdk/platform-tools:$PATH`

- **Installing an apk onto your device:**
  `adb install -r ./LinkBubble/build/outputs/apk/playstore/debug/LinkBubble-playstore-debug.apk`
- **Getting a list of devices:**
  `adb devices`
