# על הספה שלנו

A personal Android app for Steve and Roni's evening puzzle games, built for a Pixel 8 cast to the TV.
It is its own isolated browser (nothing from the personal Chrome shows on the TV), keeps the logins for
the paid sites, and fits every game to the screen.

Single user, sideloaded, no Play Store. The product brief is in `docs/HANDOFF-PROMPT.md`; the web
prototype that is the source of truth for look, copy, catalog and scoring is in `couch-artifact/`
(left untouched). The app's own copy of the hub is in `app/src/main/assets/hub/`.

**Version 1.0.1.** Everything in the brief is built: the hub (the prototype's UI, native state, import and export that
merge), the isolated game player with persistent logins (NYT, Haaretz, and a separate LinkedIn login for each of us), the
accounts screen, "who plays?" for head-to-head games, per-site TV display profiles, result capture (the game's own
Share / Copy, a native result sheet, an Android share target), a once-per-evening Do Not Disturb nudge while casting,
and a TalkBack / touch-size audit of every native screen. Nothing here was run on a phone while writing it (there is no
device in the build environment); see "Check on the phone" below for exactly what to try first.

Results (`results/`):

- **Capture.** On the game's own sites only, a script injected at document start wraps `navigator.share`,
  `navigator.clipboard.writeText` and `document.execCommand("copy")`. When the game calls one (the person pressed the game's
  own Share or Copy button), the text is passed to the app through a web-message listener, and the call goes straight on to
  the original, so the game behaves as before. The script reads nothing else (a test greps it for page, form and storage
  access) and the app re-checks every message: https origin on the allowlist, size, shape, no repeats. As a second route,
  text that appears on the clipboard while the player is in front and reads as this game's result also opens the sheet.
  A Share press always opens the sheet; a bare copy only when it parses as a result.
- **The sheet** is the prototype's result sheet, native: the captured or pasted text, what was understood and what it is
  worth ("זוהה: 3/6 · 9 נקודות ספה"), a manual field, "לא הצלחנו הפעם", and for games we play together "מי פיצח?". Nothing is
  saved until "שמירה". The scoring is the exact port from M1. A head-to-head result belongs to whoever is playing
  (`who` from the "who plays?" step) and only writes that side; the other side is never touched.
- **Share target.** "Share" in any game or app, choose "תוצאה על הספה". The game is recognised from the header
  ("Wordle 1,618 3/6", "Connections / Puzzle #512", "Queens #612 | 1:24", "I solved The Mini Crossword...");
  if it cannot be told, or two games tie, it asks instead of guessing. A head-to-head result asks "מי שיחק?" first.
- `tools/capture_check.cjs` runs the real script in Chromium: originals still called, only shares and copies reported,
  a throwing port never reaches the page.

Polish (`dnd/`, `ui/Motion.kt`, `a11y/` tests):

- **Do Not Disturb chip.** Once per evening (an evening runs until 4 in the morning), while the screen is cast, a chip
  "נא לא להפריע" appears for 15 seconds, on the hub or in the player, and opens the phone's Do Not Disturb settings. It
  does not appear when Do Not Disturb is already on. The app never changes the setting (it has no permission to, and
  should not). Android has no single "am I casting?" call, so it asks two ways (an extra display, a selected media route
  that is not the phone); if your phone reports casting some other way the chip simply will not show. That is the one
  part of this I could not check, so it logs what it sees under `AlHasapaDiag` in debug builds.
- **Accessibility.** `AccessibilityAuditTest` walks every native screen (player strip and panels, the tuning sheet, both
  result sheets, the share target, accounts) and fails if anything tappable has no spoken name or is smaller than
  48 x 48 dp, and if the couch panels use less than 20 sp. Chips and pieces are read as one thing ("רוני, משחק עכשיו").
  The hub is a web page with its own aria labels from the prototype.
- **Hebrew and right to left.** The native theme forces right to left; the one exception is the player's layout row, which
  uses the screen's real left and right (the game is centred, the panels stand beside it) and puts each panel's text
  back to right to left. Digits (the clock) are left to right inside Hebrew text.
- **Reduced motion.** With "Remove animations" on, the splash is skipped, the player's strip appears without fading,
  and the hub is told (`?rm=1`) to stop its own transitions.

TV display (`app/src/main/assets/display_profiles.json`, `display/`):

- Every site is shown one of three ways: **native** (the page as it is), **wide** (the page believes it is a 1280 px
  desktop window, scaled to the screen) or **portrait-fit** (the page believes it is a 430 x 800 phone held upright,
  scaled to the screen's height and centred, with a couch panel on each side: the game and the clock on one, the evening
  and the two of us on the other, all at 20 sp or more). Defaults follow the brief: Haaretz riddles and NYT Wordle,
  Connections, Strands, Pips and LinkedIn games are portrait-fit; the NYT daily crossword is wide; everything else is native.
- **How the page is sized.** The WebView itself is made phone-sized (430 dp wide, as tall as the box's shape needs) or
  desktop-sized (1280 dp), inside a `ScaledFrame` that shrinks the finished picture to fit the box and maps touches back
  through the same scale. The page therefore really lays out at 430 x ~800 CSS px, `100vh` really is the virtual
  height, and nothing is negotiated with the page. (This is what fixes the Haaretz chain's board falling off the bottom:
  it needs about 775 CSS px of height.) **1.0.0 got this wrong:** it set the viewport meta tag instead; the real WebView
  ignored it, the page laid out at the narrow box's width (about 210 px) and the game was cropped at both sides. A test
  now forbids the display script from touching the viewport. The display sheet shows "הדף חושב שהמסך הוא 430×800", and
  the debug log has the same line per page, so a wrong layout is a number, not a guess.
- The injected script only forces light mode and hides cookie and "get the app" banners (`css` in the defaults). It never
  reads the page. Sign-in screens (the accounts screen) are always shown as they are, never shrunk.
- **Plain http.** Android refuses cleartext traffic, and some pages redirect to `http://` (Haaretz did:
  `ERR_CLEARTEXT_NOT_PERMITTED`). A main-frame `http://` link on an allowed site is now loaded over `https://` instead.
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

## Check on the phone

Do these in order; each one tells you which part to blame if it fails.

1. **Opens.** The cats splash, then the hub (cream, the logo and tabs fixed). If it is blank or shows an error, send a
   screenshot: the debug build shows what happened on screen, and `adb logcat -s AlHasapaDiag AlHasapaHub` has more.
2. **Accounts.** Hub, "החשבונות": log in here, in the app: a login made in Chrome does not carry over, on purpose (this
   browser keeps its own cookies, so nothing from your personal Chrome shows on the TV). Log in to NYT, Haaretz, and LinkedIn once as Steve and once as Roni. Use email and
   password (Google refuses "continue with Google" inside any app, and this app does not work around that; use "לפתוח
   בכרום" if a site offers nothing else). The status shows "מחובר" once logged in. If Haaretz stays "לא ידוע", open "מה
   נשמר?" and tell me the cookie names it lists, so I can name the right one.
3. **Logins survive.** Force-stop the app, open a game from each site: you are still logged in. LinkedIn as Steve and as
   Roni are different people. (If the accounts screen says the WebView is too old for two LinkedIn logins, update
   "Android System WebView" in Google Play.)
4. **TV display.** Cast, open a Haaretz riddle (the chain): the whole board should fit, nothing cut at the sides, with a
   panel on each side. Tap the top edge, "⚙ תצוגה" (it says what size the page thinks the screen is: 430×800 in the phone-shaped mode), try "רחב" and
   "רגיל", change the zoom, "לשמור לאתר הזה", leave and come back: it is remembered.
   NYT Wordle should also be a phone-shaped box; the NYT crossword wide.
5. **Results.** Finish a Wordle, press the game's own Share: the result sheet should open filled in. If it does not, press
   "תוצאה" and paste. Try Share from a LinkedIn game and pick this app in the share list: it should ask who played.
6. **Do Not Disturb.** While casting, once per evening, the chip should appear. Tell me if it never does.
7. **Updates.** Install a newer APK over this one without uninstalling: logins and history stay.

What to expect to differ from the prototype: a few game links are Google searches standing in for a direct link (the hub
marks them "קישור זמני"; replace them under "הקישורים שלנו"), and the Haaretz chain link in the catalogue is a dated page.

## Layout

| Path | What |
| --- | --- |
| `app/src/main/java/app/couch/alhasapa/` | Kotlin: `MainActivity`, `hub/` (WebView host), `data/` (state, backups), `catalog/`, `scoring/`, `bridge/` (hub to native), `player/` and `accounts/` (the isolated browser), `display/` (TV profiles), `results/` (capture, sheet, share target), `dnd/`, `ui/` (splash, theme) |
| `app/src/main/assets/hub/` | The prototype's web UI, `pieces.json`, bundled fonts and images |
| `app/src/main/res/font/` | IBM Plex Sans Hebrew for native screens (the hub has its own copy in `assets/hub/fonts/`) |
| `app/src/main/assets/games.json` | The game catalogue (sites and games). Normally you only change a game's `u` (its link), or edit it in the app ("הקישורים שלנו") |
| `app/src/main/assets/display_profiles.json` | How each site is shown on the TV (mode, zoom, banner CSS) |
| `tools/` | `make_illustration_masks.py` (illustration masks), `extract_catalog.mjs` (catalogue from the prototype), `gen_scoring_vectors.mjs` (golden vectors recorded from the prototype's own scoring), `display_check.cjs` and `capture_check.cjs` (the injected scripts, run in real Chromium) |
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
