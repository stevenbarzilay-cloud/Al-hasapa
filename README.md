# על הספה שלנו

A personal Android app for Steve and Roni's evening puzzle games, built for a Pixel 8 cast to the TV.
It is its own isolated browser (nothing from the personal Chrome shows on the TV), keeps the logins for
the paid sites, and fits every game to the screen.

Single user, sideloaded, no Play Store. The product brief is in `docs/HANDOFF-PROMPT.md`; the web
prototype that is the source of truth for look, copy, catalog and scoring is in `couch-artifact/`
(left untouched). The app's own copy of the hub is in `app/src/main/assets/hub/`.

**Status:** milestone 3 (TV display). The data layer, the isolated player with persistent logins (including two LinkedIn
profiles), the accounts screen, "who plays?" for head-to-head games, and per-site display profiles are in. Still to
come: result capture (share / copy hook, native result sheet, Android share target) and the polish pass.

TV display (`app/src/main/assets/display_profiles.json`, `display/`):

- Every site is shown one of three ways: **native** (the page as it is), **wide** (the page believes it is a 1280 px
  desktop window, scaled to the screen) or **portrait-fit** (the page believes it is a 430 x 800 phone held upright,
  scaled to the screen's height and centred, with a couch panel on each side: the game and the clock on one, the evening
  and the two of us on the other, all at 20 sp or more). Defaults follow the brief: Haaretz riddles and NYT Wordle,
  Connections, Strands, Pips and LinkedIn games are portrait-fit; the NYT daily crossword is wide; everything else is native.
- Not by transform. The WebView is sized to the box and a script injected at document start sets the viewport
  (`width=` the virtual width, scale fixed), so the page lays out for that screen and `100vh` really is the virtual
  height (this is what fixes the Haaretz chain's board falling off the bottom: it needs about 775 CSS px of height and
  now gets 800). The same script forces light mode and hides cookie and "get the app" banners (`css` in the defaults).
  It never reads the page. If a WebView is too old for document-start scripts it runs on page start instead.
- **Zoom** shrinks the virtual viewport (125% means 344 px wide instead of 430). **Safe area** keeps 2.5% free on every
  side for TVs that crop; the camera cut-out is respected on top of that.
- Tuning is live: top edge, then "תצוגה" opens a sheet on the left; mode and zoom change the page as you press.
  "לשמור לאתר הזה" stores it under host + path prefix (the longest match wins; stored in the state as `displays`,
  survives backups); "לחזור לברירת המחדל" removes it again.
- Three sites in the defaults are my guesses, not in the brief (the NYT Mini, the NYT puzzles section, and the
  LinkedIn games index): they are marked `_note` in the file.
- `tools/display_check.cjs` runs the real injected script in Chromium (mobile emulation) against pages with and without
  their own viewport meta.

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
- **The illustrations follow the pieces' colours.** Each picture is the untouched original plus two layers, one per
  colour family in the art (red = Steve: his shirt, the mug, cushion arcs, socks, the orange cat, the red game
  piece; pink = Roni: her shirt, the couch, the pink piece and squares). A layer is a flat colour cut out by a soft
  mask and blended with `mix-blend-mode:color`: new hue and saturation, each pixel keeps its own brightness, so
  shading and linework survive. Skin, ink, hair and cream are in neither family and are never touched. Steve's
  region is first brightened in proportion to how much lighter his colour is than the original red, so light
  colours (mustard, orange) stay vivid. With the default red/pink the layers are off and the art renders
  pixel-identically to the original. The masks are generated by `tools/make_illustration_masks.py` (committed).
  The native splash and the launcher icon are static for now; the splash follows once the state lives natively (M1).
- A blank screen is never an acceptable failure. If the page does not load, or loads but does not draw itself
  within 4 s, a native error screen shows the reason with a retry button; if the page's own script crashes, the
  error is printed on the page. Everything the page logs goes to Logcat under the tag `AlHasapaHub`
  (`adb logcat -s AlHasapaHub`).
- Debug builds keep a startup log (activity and lifecycle events, the device's WebView version, splash events, page
  events, what the page reports about itself, and a capture of the real composited window with the share of
  non-background pixels). It is written to Logcat under `AlHasapaDiag` and kept in memory, and it is **invisible**:
  it shows on screen (plain Android views, so it works even if Compose or the WebView fail) only when a problem is
  recorded. If the hub is blank although the page says it drew, the host rebuilds the WebView up to twice, then
  shows the error screen.
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
| `app/src/main/assets/games.json` | The game catalogue (sites and games). Normally you only change a game's `u` (its link), or edit it in the app ("הקישורים שלנו") |
| `tools/` | `make_illustration_masks.py` (illustration masks), `extract_catalog.mjs` (catalogue from the prototype), `gen_scoring_vectors.mjs` (golden vectors recorded from the prototype's own scoring) |
| `THIRD_PARTY/` | Font license (SIL OFL 1.1) |
| `couch-artifact/` | The original prototype and the Haaretz layout bug screenshots, kept verbatim |

## Data

- `StateRepository` owns the state (`filesDir/state.json`, replaced atomically). Every change bumps a revision; the hub saves
  with the revision it last saw and a stale save is refused, so a change made natively (a result saved from the player)
  is never overwritten by a page that was in the background. The hub reloads its state whenever the app returns to the front.
- Schema = the prototype's (`v, names, prefs, jokes, log[day][game] = {w, r?, d?}`) plus additive `pieces`, `urls`, and a
  `t` timestamp on entries. Unknown fields are preserved.
- Import merges: per day and game the newer entry (`t`) wins, head-to-head sides merge side by side, a prototype backup
  without timestamps lets the richer entry win, names changed from the default win, jokes are unioned, nothing is deleted.
- First run migrates whatever the page kept in its own localStorage before this layer existed.
- Deviation from the brief: a single atomic file instead of DataStore/Room. The page needs a synchronous read at boot and one
  JSON document needs nothing more; the `StateStore` interface is the seam where sync could be added later.

## Principles that tests guard

- The hub never touches the network: no remote fonts, scripts or styles (`HubAssetsTest`), and the
  WebView client refuses any non-local request.
- No automatic backup of logins or history. Moving history between phones is the in-app export/import.
