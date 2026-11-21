# על הספה שלנו

A personal Android app for Steve and Roni's evening puzzle games, built for a Pixel 8 cast to the TV.
It is its own isolated browser (nothing from the personal Chrome shows on the TV), keeps the logins for
the paid sites, and fits every game to the screen.

Single user, sideloaded, no Play Store. The product brief is in `docs/HANDOFF-PROMPT.md`; the web
prototype that is the source of truth for look, copy, catalog and scoring is in `couch-artifact/`
(left untouched). The app's own copy of the hub is in `app/src/main/assets/hub/`.

**Status:** milestone 0 (skeleton). The hub opens and works offline. Game links, accounts, TV mode,
result capture and the real storage layer come in the next milestones.

## Build

Requirements: JDK 17+ (21 works), Android SDK with platform `android-37.2` and build-tools `37.0.0`.
Android Studio installs those for you. If you open this folder in Android Studio and it asks to
update the Android Gradle Plugin or the SDK, let it.

```sh
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # unit tests (JVM, no device needed)
./gradlew :app:lintDebug            # -> app/build/reports/lint-results-debug.html
```

Outside Android Studio, point Gradle at the SDK with `ANDROID_HOME=/path/to/sdk` or a
`local.properties` containing `sdk.dir=/path/to/sdk` (that file is git-ignored).

## Install on the Pixel 8

**With Android Studio:** open the folder, pick the Pixel 8 in the device list, press Run.

**With adb** (USB debugging on, phone connected):

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Without a computer:** copy `app-debug.apk` to the phone, open it from Files, and allow
"install unknown apps" for that file manager when Android asks.

**Updating:** just install the new APK over the old one. Logins and history are kept, because every
build is signed with the same committed debug key (`app/debug.keystore`, the standard public
`android` passwords, so it is not a secret). Do not uninstall first, that wipes the logins. If Android
says "package conflicts with an existing package", the installed copy came from a different key
(for example an APK built before this key existed); in that case export a backup, uninstall, and
install again.

## Layout

| Path | What |
| --- | --- |
| `app/src/main/java/app/couch/alhasapa/` | Kotlin: `MainActivity`, `hub/` (WebView host), `ui/theme/` |
| `app/src/main/assets/hub/` | The prototype's web UI plus bundled fonts and images |
| `app/src/main/res/font/` | IBM Plex Sans Hebrew for native screens (the hub has its own copy in `assets/hub/fonts/`) |
| `THIRD_PARTY/` | Font license (SIL OFL 1.1) |
| `couch-artifact/` | The original prototype and the Haaretz layout bug screenshots, kept verbatim |

## Principles that tests guard

- The hub never touches the network: no remote fonts, scripts or styles (`HubAssetsTest`), and the
  WebView client refuses any non-local request.
- No automatic backup of logins or history. Moving history between phones is the in-app export/import.
