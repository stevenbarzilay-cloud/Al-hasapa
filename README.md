# על הספה שלנו

A personal Android app for Steve and Roni's evening puzzle games, built for a Pixel 8 cast to the TV.
It is its own isolated browser (nothing from the personal Chrome shows on the TV), keeps the logins for
the paid sites, and fits every game to the screen.

Single user, sideloaded, no Play Store. The product brief is in `docs/HANDOFF-PROMPT.md`; the web
prototype that is the source of truth for look, copy, catalog and scoring is in `couch-artifact/`
(left untouched). The app's own copy of the hub is in `app/src/main/assets/hub/`.

**Status:** milestone 0, second round (skeleton + phone feedback). The hub opens and works offline,
the launcher icon and an animated splash use the three-cats illustration, and each of you can pick a
shape and a colour for your piece (the whole interface follows the colour). Game links, accounts, TV
mode, result capture and the real storage layer come in the next milestones.

Hub notes:

- The logo and the tab bar never move; only the content scrolls. If anything is ever wider than the screen,
  a scroll indicator (track, thumb, arrows) appears immediately above the tabs.
- Pieces live in `app/src/main/assets/hub/pieces.json` (shapes, colours with `base` / `accent` / `soft`
  tones). Steve's colour drives the "red" role of the interface and Roni's the "pink" role. The tests check
  that every colour stays readable. The choice is stored in the state JSON as `state.pieces` (additive:
  older backups without it still import).
- **WebView layout-height trap.** Android's WebView can report a layout height of 0 to the page (innerHeight stays
  correct), which collapses `height:100%`, `100vh` and `bottom:0`. In M0.1-M0.3 that blanked the whole hub: the page
  said it had drawn, but its scroll area was 0 px tall. The WebView now has explicit match-parent layout params, and
  the hub shell is `position:fixed` with a pixel height set from `innerHeight` (`--app-h`); the sheet and toast live
  inside it. `HubAssetsTest.theShellNeverDependsOnPercentageHeights` keeps it that way. When testing in a desktop
  browser, force `html,body{height:0!important}` to emulate the condition.
- Diagnostics: tap the logo three times to show what size the page believes the screen is.
- A blank screen is never an acceptable failure. If the page does not load, or loads but does not draw itself
  within 4 s, a native error screen shows the reason with a retry button; if the page's own script crashes, the
  error is printed on the page. Everything the page logs goes to Logcat under the tag `AlHasapaHub`
  (`adb logcat -s AlHasapaHub`).
- Debug builds show a small on-screen startup log (plain Android views, so it works even if Compose or the
  WebView fail): activity and lifecycle events, the device's WebView version, splash events, page events, what the
  page reports about itself, and a capture of the real composited window with the share of non-background pixels.
  It fades away a few seconds after the hub is confirmed visible and stays up if anything went wrong. If the hub is
  blank although the page says it drew, the host rebuilds the WebView up to twice, then shows the error screen.
  The same lines go to Logcat under `AlHasapaDiag`.
- `AppRootTest` runs the startup flow (hub created and told to load, splash lifts, hub stays mounted, error
  screen and retry) through the real Android framework with Robolectric. It cannot run Chromium, so page
  rendering itself is checked separately in a browser.

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
| `app/src/main/java/app/couch/alhasapa/` | Kotlin: `MainActivity`, `hub/` (WebView host), `ui/` (animated splash), `ui/theme/` |
| `app/src/main/assets/hub/` | The prototype's web UI, `pieces.json`, bundled fonts and images |
| `app/src/main/res/font/` | IBM Plex Sans Hebrew for native screens (the hub has its own copy in `assets/hub/fonts/`) |
| `THIRD_PARTY/` | Font license (SIL OFL 1.1) |
| `couch-artifact/` | The original prototype and the Haaretz layout bug screenshots, kept verbatim |

## Principles that tests guard

- The hub never touches the network: no remote fonts, scripts or styles (`HubAssetsTest`), and the
  WebView client refuses any non-local request.
- No automatic backup of logins or history. Moving history between phones is the in-app export/import.
