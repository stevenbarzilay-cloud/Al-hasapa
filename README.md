# על הספה שלנו

A personal Android app for Steve and Roni's evening puzzle games, built for a Pixel 8 cast to the TV.
It is its own isolated browser (nothing from the personal Chrome shows on the TV), keeps the logins for
the paid sites, and fits every game to the screen.

Single user, sideloaded, no Play Store. The product brief is in `docs/HANDOFF-PROMPT.md`; the web
prototype that is the source of truth for look, copy, catalog and scoring is in `couch-artifact/`
(left untouched). The app's own copy of the hub is in `app/src/main/assets/hub/`.

**Version 1.4.0.** Everything in the brief is built: the hub (the prototype's UI, native state, import and export that
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
- **Zoom** has two parts that you never have to tell apart. Up to 140% the page is told its screen is smaller (125% means
  344 px wide instead of 430) and lays itself out again: nothing is cropped. A phone-sized page breaks if squeezed
  much further, so from 140% to 300% the finished picture is magnified instead: it gets bigger and the edges are cropped, and
  the arrows in the display sheet move which part of the page stays in view (kept per site). The strip at the top has "−" and "+"
  that keep what they set for that site at once; the display sheet has the same plus the arrows.
- **How big the game can be.** In portrait-fit the game's size is set by the screen's height (a 430 x 800 page on a 390 dp
  tall screen is about half size); the panels only use what is left over. So the panels never take size from the game, but
  they are still responsive: full above 280 dp each, essentials only (the game, the clock, the count, the result button) down to
  150 dp, and below that the same UI becomes a top bar instead of vanishing (see "The always-visible UI"). "רחב" uses the whole screen
  (the page believes it is a 1280 px desktop window), which makes the content larger than portrait-fit when the site's
  desktop layout works at that height. The TV margin (0 / 2.5 / 5%) is in the sheet too: 0 gives about 5% more game.
- **Safe area** keeps 2.5% free on every side for TVs that crop (the camera cut-out is respected on top of that).
- **The always-visible UI** (1.1.0). There is no menu that disappears: the couch UI is on screen the whole time, and the hamburger
  (☰) is always one tap from every setting. Where it sits is a per-site choice, made in the menu: **beside the game** (two
  panels, for the phone-shaped mode), **a bar on top**, **a bar at the bottom**, **top and bottom** (the game, the clock and the zoom
  above; the evening, the two of us and the buttons below), or **just the button** (it floats in the corner and costs the
  game nothing). It comes in three sizes: small bars are 48 dp (the touch minimum), medium 60, large 76 with bigger text (20 sp
  at least everywhere). Bars drop items as the room gets short (the pieces first, then the points, the count, the zoom, the clock
  and the title) and never drop the hamburger, the back button, "we played" or the result. By default a phone-shaped game gets
  panels and everything else a small top bar; if the panels do not fit the screen the same UI becomes a top bar. Bars cost
  the game some height, panels and the single button cost none.
- The menu (☰) holds the quick actions (back, result, "we played") and the live tuning: mode, zoom, the arrows, where the UI
  sits and how big it is, and the TV margin. Changes show on the page as you make them; "לשמור לאתר הזה" (pinned at the foot of
  the menu) stores them under host + path prefix (the longest match wins; stored in the state as `displays`, survives backups);
  "לחזור לברירת המחדל" removes it again. The "−" and "+" next to the clock save the zoom at once.
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
./gradlew :app:assemblePhoneDebug   # -> app/build/outputs/apk/phone/debug/app-phone-debug.apk   (the phone app)
./gradlew :app:assembleTvDebug      # -> app/build/outputs/apk/tv/debug/app-tv-debug.apk         (the Android TV app, debug)
./gradlew :app:assembleTvRelease    # -> app/build/outputs/apk/tv/release/app-tv-release.apk     (the TV app, optimised: use this on the box)
./gradlew :app:testPhoneDebugUnitTest :app:testTvDebugUnitTest   # unit tests (JVM, no device needed)
./gradlew :app:lintPhoneDebug :app:lintTvDebug                   # -> app/build/reports/lint-results-*.html
```

Outside Android Studio, point Gradle at the SDK with `ANDROID_HOME=/path/to/sdk` or a
`local.properties` containing `sdk.dir=/path/to/sdk` (that file is git-ignored).

## Install on the Pixel 8

**With Android Studio:** open the folder, pick the Pixel 8 in the device list, press Run.

**With adb** (USB debugging on, phone connected):

```sh
adb install -r app/build/outputs/apk/phone/debug/app-phone-debug.apk
```

**Without a computer:** copy `app-debug.apk` to the phone, open it from Files, and allow
"install unknown apps" for that file manager when Android asks.

**Updating:** just install the new APK over the old one. Logins and history are kept, because every
build is signed with the same committed debug key (`app/debug.keystore`, the standard public
`android` passwords, so it is not a secret). Do not uninstall first, that wipes the logins. If Android
says "package conflicts with an existing package", the installed copy came from a different key
(for example an APK built before this key existed); in that case export a backup, uninstall, and
install again.

## The Android TV app (Xiaomi Mi Box and other Android TV)

The same code, built as a second app (`app.couch.alhasapa.tv`, shown in the TV launcher with its own banner). It runs the games
on the TV itself, so there is no casting and no lag. It needs Android 8.0 or newer (API 26); a Mi Box S runs 8.1 or 9. It keeps
its own history and logins (it is a different app); until accounts and sync exist, bring over history with the hub's backup.

**Install** (once): on the Mi Box, Settings, Device Preferences, About, press Build seven times; then Developer options, turn on
USB debugging (and network debugging). From a computer on the same network:

```sh
adb connect <the Mi Box's IP address>:5555
adb install -r app-tv-debug.apk
```

Without a computer: put the APK on a USB stick and open it with a file manager app from the Play Store, or use "Send Files to TV".
Later versions install over the old one (same key), so the logins stay.

**Use the release build on the box.** `assembleTvRelease` is the same app with R8 shrinking (about 4 MB instead of 33) and no debug
overhead, signed with the same committed key, so it installs over a debug build and keeps the logins. The debug build is for development;
on a weak TV box it is noticeably slower to start and to scroll.

**The remote: two modes, always visible.** A dock on the left edge of every screen holds the **paw** and the **keyboard**; in a game it
also holds everything about the game's frame (below).

- **Navigate (the default).** The arrows move the highlight from button to button, in the direction pressed, like Tab: through the
  app's own buttons, and *into the pages* too: the hub's buttons, the games' buttons, links and fields (a small script finds what can
  be pressed; it only reads where things are, never what they say). The focused button is **filled with colour** (a red fill in the
  button's own shape, no square ring); on a page the highlight is a translucent red fill in the element's own shape. **OK** presses
  what is highlighted (as a real tap at its centre, so every game gets the touches it expects). When there is nothing further, the
  page **scrolls**; at the end of the page the highlight moves on to the dock, and back. Left from the game goes to the dock.
- **Paw (the mouse), only when needed.** Press the paw button (a dark circle with a red paw while it is on, and a message at the
  top says which mode you are in): the arrows now move a free paw print, slowly at first and faster the longer an arrow is held,
  **OK** taps where it is, **hold OK** puts the finger down (the paw turns red) so the arrows drag, hold OK again to lift. Use it for
  games with nothing to select (a canvas, a grid you swipe across). If a page has nothing the highlight can land on, the app says so
  and points at the paw. The paw button switches back.
- **Back** and **Home** are the system's. The Mi Box remote has no Menu key, so the menu is the ☰ button in the dock.

**The dock in a game (one frame, not bars and panels).** On a TV there are no bars above and below the game and no side panels. The
whole frame is the dock at the left, and the game keeps the middle: the clock with the logo, the evening's score and the two of
us; **−** and **+** (zoom, kept per site); **▯ / ▭** (phone-shaped or wide); **▣ / ◧** (the frame itself: full, or slim with only
the buttons as icons; remembered); the paw; the keyboard; ☰ and ↩; and, with words, "result" and "we played". Every button says what
it is at the top of the dock while it is highlighted.

**The keyboard.** The keyboard button in the dock (or the remote's Play/Pause key, or a text field taking focus) slides it up
from the bottom. It is small: four rows and a slim header that shows what you typed, about a third of the screen, and the game
area shrinks to make room, so you still see the game while you type. English, Hebrew and symbols (digits are on the symbols
layout); Shift makes one capital; the arrows move a highlight, OK types, **Back** or ✕ closes it. A password field shows dots. Letters
go in as real key presses (a game that listens for keys, like Wordle, just works); Hebrew and symbols are inserted as text. The
system keyboard is kept away so the two never fight. Sign-in works the same way: highlight the field, OK, type, highlight "Sign in".

**Sound and video** play as on the phone, and a game's full-screen video fills the screen (Back leaves it).

What cannot be checked without the box: how the highlight moves around real pages (it is tested in Chromium on a sample page, not on every game), how fast the paw feels, whether the keyboard reaches the page on this WebView, and whether
the WebView on the Mi Box is recent enough for the games. The Mi Box's own WebView version matters most (Settings, Apps, show system
apps, Android System WebView): the oldest ones cannot run some modern pages.

**Starting up and crashes (1.4).** On the TV the splash is no longer animated: the system's own starting window shows the cats at
once, and the hub fades in when it is ready (the animated splash made a weak GPU stutter, and before it the screen stayed blank
for seconds). The hub itself runs in a lighter mode on the TV (no blurs, shadows or long animations). If the app ever crashes, the
next start opens a screen with what happened and the timings of the last start (it reaches nothing outside the device; the same
text is under Settings, "על האפליקציה", with a copy button), so one screenshot tells us what failed.

## Settings and your own games (1.4)

The hub has a **הגדרות** tab (on the phone and the TV):

- **The links of the games.** Every game has a field with its link. Paste a direct link (such as today's puzzle), save, and the
  game opens there from then on; an empty field restores the built-in link. Games that still open only a site's main page are
  marked "עמוד ראשי בלבד", and temporary placeholders are marked "קישור זמני".
- **Add a game.** A name, an https:// link, a kind, how a result is counted (none, tries, time, hints) and whether it goes straight on
  the shelf. Your own games live under the site "שלנו", are played in the same isolated player, keep their results and points,
  and travel inside the backup. Delete one with "למחוק את המשחק הזה".
- **Add several at once.** One game per line: `name | https://link | kind` (the kind is optional).
- **Accounts** (NYT, Haaretz, the two LinkedIn logins) and **About** (version, device, startup times, the last crash).

Google sign-in is not offered anywhere, because Google blocks it inside an embedded browser; use email and password.

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
   panel on each side. Tap ☰ (it is always there; the menu says what size the page thinks the screen is: 430×800 in the phone-shaped mode), try "רחב" and
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
| `app/src/tv/` | What is only in the TV build: the TV launcher entry and its banner |
| `app/src/main/java/app/couch/alhasapa/` | Kotlin: `MainActivity`, `hub/` (WebView host), `data/` (state, backups), `catalog/`, `scoring/`, `bridge/` (hub to native), `player/` and `accounts/` (the isolated browser), `display/` (TV profiles), `results/` (capture, sheet, share target), `dnd/`, `tv/` (the remote's focus navigation, the paw, the keyboard, the dock, full-screen video), `diag/` (startup timings, crash report), `ui/` (splash, loading cover, crash screen, theme) |
| `app/src/main/assets/hub/` | The prototype's web UI, `pieces.json`, bundled fonts and images |
| `app/src/main/res/font/` | IBM Plex Sans Hebrew for native screens (the hub has its own copy in `assets/hub/fonts/`) |
| `app/src/main/assets/games.json` | The game catalogue (sites and games). Normally you only change a game's `u` (its link), or edit it in the app ("הקישורים שלנו") |
| `app/src/main/assets/display_profiles.json` | How each site is shown on the TV (mode, zoom, banner CSS) |
| `tools/` | `make_illustration_masks.py` (illustration masks), `extract_catalog.mjs` (catalogue from the prototype), `gen_scoring_vectors.mjs` (golden vectors recorded from the prototype's own scoring), `display_check.cjs`, `capture_check.cjs`, `tvnav_check.cjs` and `hub_settings_check.cjs` (the injected scripts and the hub's settings page, run in real Chromium) |
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
