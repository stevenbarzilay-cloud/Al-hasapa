# על הספה שלנו

A personal Android app for Steve and Roni's evening puzzle games, built for a Pixel 8 cast to the TV.
It is its own isolated browser (nothing from the personal Chrome shows on the TV), keeps the logins for
the paid sites, and fits every game to the screen.

Single user, sideloaded, no Play Store. The product brief is in `docs/HANDOFF-PROMPT.md`; the web
prototype that is the source of truth for look, copy, catalog and scoring is in `couch-artifact/`
(left untouched). The app's own copy of the hub is in `app/src/main/assets/hub/`.

**Version 1.11.2.** Everything in the brief is built: the hub (the prototype's UI, native state, import and export that
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

**1.9: a TV app of its own.** Three changes after the evening of 23 November 2026, when the people asked for a TV app they would
want to play on, starting with Krillion and NYT:

- **The TV's own home** (`home/TvHome.kt`, `home/HomeNav.kt`). The TV no longer opens the phone's web hub (four to six seconds to be
  ready on the box): it opens a native home at once. At the top the day, the evening so far ("שיחקנו 1 מתוך 25 · 2 נקודות ספה") and
  the two of us; then "המדף של הערב", the shelf as a row of big cards (what is left to play first, the played ones after with ✓), and
  "עוד משחקים", the rest; under them what the card in focus is; at the foot "החשבונות", "הגדרות ועוד" (the full web hub as before:
  the links, adding a game, the backup; the cats cover it while it loads, and Back from it returns to the home) and "מידע לתיקון
  תקלות", and whether the phone is connected. There is no dock on the home (nothing there to point at or type into), and everything
  fits one TV screen.
  The remote moves by the home's shape, like a TV's own home screen: Left and Right walk along a row to its very end (the row moves so
  the card in focus sits near its start, the card before it peeking in), Up and Down change rows and each row keeps its place, at the
  edges the focus stays. The card in focus grows and wears a red ring; OK opens it; after a game was played the focus is on the next
  one left. Only the cards near the screen exist at any moment (a lazy row), so the home is light for the box.
- **Sign in on the phone, the TV gets it** (`remote/LoginHandoff.kt`, `remote/LoginCrypto.kt`). Typing a password with a remote is
  miserable, and NYT's sign-in page stayed white on the box. In keyboard mode on the phone, "התחברות לטלוויזיה" lists the sites whose
  login can go (NYT and Haaretz: the sites of the accounts screen that share the TV's one browser), says whether the phone is signed in
  to each, and sends it. What goes is what the phone's browser holds for the site (its cookies, each set on the TV for the site's own
  domain, https only, for about two months; anti-bot cookies stay behind, they belong to the phone), and only to the paired TV: for
  each handoff both sides make a fresh P-256 key and agree on a secret (ECDH), the TV signs its key with the pairing token (HMAC), the
  key that encrypts the login comes from the secret and the token (HKDF), and AES-256-GCM refuses anything changed on the way. Values
  are never written to a log, only how many. The TV says "ההתחברות ל-NYT הגיעה מהטלפון". The per-person LinkedIn logins do not go (the
  TV keeps one browser profile).
- **The pairing token no longer crosses the network after pairing.** The TV's greeting carries a new nonce each time and the phone
  answers with a proof (HMAC of the nonce under the token) instead of the token. A phone app from before 1.9 still types (it sends the
  token as before) but cannot hand over a login.
- **1.9.1, two mistakes of 1.9.0 found before it reached the TV.** On the phone every game opened from the hub crashed the app: the
  function that opens a game and the hub's own `play` had the same name, and the one called itself until the stack ran out (nothing
  tested what the hub's page asks of the app; `MainActivityHubActionsTest` now presses what the page presses, and fails with that very
  crash on the old code). And on the TV's home the arrows could not get past the fifth card of a row: 1.9.0 moved by where buttons are
  on the screen, and a card scrolled out of sight is nowhere (`TvHomeRemoteTest` now walks every card of the shelf on a TV-sized
  screen, checks each is wholly on the screen when in focus, and opens Krillion with OK). Also: with the information panel open the
  arrows no longer wander to buttons behind it; a `__Host-` cookie is handed over for its own host (a browser refuses it with a
  Domain); a login can be sent when the phone cannot tell whether it is signed in (Haaretz); and on a TV each screen asks to keep the
  display mode that is on (a precaution: a page that plays video can make a box change the HDMI mode, which blanks the TV).
- **1.9.2: Krillion in its own low-power mode on the TV.** Krillion (seven prompts, 25 seconds each, typed) froze the box's picture
  once and was too slow to play. Looked at here in Chromium at the TV's page size: it draws two full-screen canvases sixty times a
  second (at the screen's full pixel count), runs eleven endless animations and keeps a layer 27 screens tall ready to move. With the
  processor slowed six-fold that is about 10 frames a second with the page's thread saturated. The game has a setting of its own for
  weak devices, "Low-power mode" (a quarter of the canvas pixels, 30 frames a second, fewer particles, the decorative animations
  paused): about 49 frames a second in the same measurement, and a typed key on the screen within 50 ms. On a TV the app now switches
  that setting on when the game opens, unless the people chose otherwise in Krillion's own settings (which stays as they left it):
  `display_profiles.json` names the site's own preference (`tvStorage`), and the display script sets it before the page's first script
  runs, only when it is not there, touching nothing else (`tools/display_check.cjs` checks all of that in Chromium, and the generated
  script was run against the real page). The answer field takes the keys by itself and Enter sends, so the phone's keyboard is all
  the game needs after "BEGIN DESCENT".
- **1.9.3: each Dialed card opens its own game.** "Dialed · צבע" and "Dialed · צליל" both opened `https://dialed.gg`, the site's
  showcase page (twice the size of a game's page, with animated demos), on which the game still had to be found; on the TV box it did
  not come up. They now open `https://dialed.gg/color` and `https://dialed.gg/sound` (the prototype in `couch-artifact/` keeps the old
  links; a link saved in the hub's settings still wins). Whether that is all Dialed needs on the box is not known: nothing in its
  pages turns a browser away or sends it to another site, so if it still does not come up, the report (ⓘ) now says what happened to
  the page.
- **1.9.4: sound that can be heard on the TV, a paw that keeps up, signing in where one looks for it, and fourteen new games.**
  What came back from the box on 1.9.3: Krillion fair, Dialed's colour game not bad, Dialed's sound game choppy to the point of
  not being playable, the paw slow on the home, "where do I sign in?", and the phone app crashing on an NYT game.
  - *Sound.* Dialed makes its sound itself (Web Audio: a handful of plain tones, nothing heavy) and asks the browser for an audio
    context with no wishes, which means the setting for the shortest delay: small pieces of sound made at the last moment and handed
    to the box's low-delay output. Its page also keeps the main thread busy without a pause (measured at a sixth of a desktop's speed:
    7 frames a second, the thread never idle; the page's "reduced motion" does not change that). A piece that comes late is a gap.
    A page's `<audio>` and `<video>` do not go that way: the browser plays them through its music output, with a longer buffer. So on
    a TV a page that asks for no buffer of its own now gets the browser's music buffer (`latencyHint: "playback"`), set before the
    page's first script runs (`SoundScript`; a page that asks for a longer buffer keeps its wish, and to the page an audio context
    is what it always was: `tools/sound_check.cjs` checks all of that in Chromium). The script also counts, and the report (ⓘ) says
    for each game what its sound was given, how long it ran and how many gaps the browser counted; for `<audio>` and `<video>`, how
    often they stopped to wait and how many video frames were dropped; and for any page how much of the time its main thread was busy
    (`PageBusy`: while it is, the page answers no press, which is what "the mouse is slow" on Dialed's first screen was). A site where
    four or more gaps are counted gets the longest buffer the browser gives (about a fifth of a second) from the next time it opens,
    by itself (`SoundPolicy`). The cost is a delay of a fraction of a second between a press and its sound. **Not heard on the box:**
    that the low-delay output is what broke up the sound there is the likely reading of how the browser is built, not something
    measured; the next report says whether gaps are still counted.
  - *The paw.* It was drawn on a canvas as large as the screen, so every move had the whole home painted again; it is now a small
    picture of its own that is only moved (and a card of the home is one layer instead of three). And the TV asks its Wi-Fi not to
    doze while a phone is connected (`WifiLatency`, Android's low-latency Wi-Fi lock): a device that is only listening collects its
    packets a few times a second, which a touchpad shows as a paw that moves in jumps, most of all on the home, where nothing else
    keeps the radio awake. Each stroke of the touchpad is now in the report in numbers (`PadTiming`): how far apart the moves
    arrived, how long they were held up on the way (the phone stamps each move), how long they waited for the TV.
  - *Signing in.* The handoff from 1.9 was behind a button in keyboard mode and said nowhere else. Now the TV's own screen
    ("התחברות לאתרים" on the home) opens with the three steps; in the phone's keyboard mode the list ("התחברות לטלוויזיה") signs in
    and sends in one place ("להתחבר כאן", then "לשלוח"); and the phone's accounts screen offers "לשלוח את ההתחברות לטלוויזיה" for a
    login made there.
  - *The crash was 1.9.0 again.* The report's build id is the 1.9.0 phone build's: the TV was updated three times, the phone not
    once. The two now tell each other their version when they connect, and the one that is behind is named on the TV's home and
    in the phone's keyboard mode. A crash report says the app's version and the error above the text that scrolls, and a stack that
    goes round in circles is said once (the nine screenshots of that crash showed the same two lines and neither fact).
  - *The catalogue.* Eight games are on the shelf from the start (Songless, Framed, Catfishing, Originle, The Dozen, Disorderly,
    Magnitudle: Size It Up, and Bandle, which was there) and ten are offered first under the cushions, in the order they were given
    (`pick` in `games.json`; TimeGuessr and Cinematrix were there and got their direct links, Framed moved from the suggestions to
    the shelf; where one of these three had been skipped or put off on a device, that is forgotten once, `CatalogUpdate`, so they
    show where they were asked for). `games.json` is the app's own catalogue from here on (the prototype in `couch-artifact/` keeps
    its 54).
    Only Originle, The Dozen and the two Magnitudle games could be opened from the build environment; the other links are as given.
- **1.9.5 (TV): the home gets its illustrated look back.** The home had been pared down to plain cards to run well on the box. Its
  look now follows a brief and a demo page from the two of us (kept out of the repository; the demo was a reference for three things:
  where the logo and the picture go, what a cushion looks like, how the selection walks along a shelf). Only how the TV's home is
  drawn changed; the phone is as it was.
  - *What it looks like.* Our logo at the top right; the two of us and our pieces at the top left, and under the names, from the same
    margin, the picture of us on the couch with the cat (both are the app's original files, `assets/hub/img`, read once at the size
    the screen needs: `HomeArt`). At the picture's side, what the button in focus is: a game's kind and site, its name in full, the
    sentence about it; for the app's own buttons, what they are for. Every game is a cushion on a shelf: straight, one size, on one
    line, at even gaps, also in focus. The fabric's folds and the prints at its corners (the circle, the stair, the zigzag) are the
    ones on the cushions in our picture; in the middle a symbol for the kind of game (words, logic, quiz, film, music, places,
    sight: the same kind, the same symbol) and under it the game's name.
  - *The colours* come from the two colours we chose for our pieces: fabric, print and the wall behind the shelves. Which fabric and
    print a game gets is decided by its id, so a cushion is the same wherever it stands and whatever was played (`CushionColors`,
    the demo's arithmetic to the digit: the tests hold golden values made with the demo's own rule). On top of that rule the name
    and the symbol are made sure of: for every game and every pair of the colours the app offers, the same one twice too, they read
    at 4.5 to 1 or better against their fabric (the rule alone leaves some two hundred of those 4,352 below that).
  - *The focus* is a double thread around the cushion's edge, a light one and a dark one. The red that marked the focus until now is
    kept outside them until the threads have been seen on the TV itself (one argument of `CushionArt.threads` takes it away).
  - *What did not change:* the games, their order and the two shelves come from the same model; the remote moves by `HomeNav` as
    before (along a shelf to its very end, up and down between the shelves and the buttons, each row keeping its place); every
    cushion is the same button with the same id; only the cushions near the screen exist (the same lazy row, which moves so that the
    one in focus stands at the shelf's start); OK, coming back from a game, the focus moving on after a game was played. Sign-in,
    "הגדרות ועוד", the information panel, the line about the phone, the day, the count and the points are all still there.
  - *What a press costs.* A cushion is some fifteen shapes. They exist once for all cushions; each cushion's inside is cut to its
    edge when the shapes are made (not while drawing); a cushion is drawn once into a picture of its own that the box then only
    moves; and the focus's thread is drawn apart from that picture, so moving the focus draws three lines around two cushions.
    Nothing animates but the shelf's own slide (the old card's growing in focus is gone). Measured on the build machine, the shelf
    walked there and back three times (`TvHomeZapTimingTest`): the work the app does for a press (composing, laying out, issuing
    the drawing) is what it was, 8.2 ms against 9.0 ms before along a shelf and 5.0 against 3.9 between rows. With every frame
    painted in software, which is how that machine draws and not how a TV does, a press costs 58 ms against 17 ms: there every
    cushion is painted from its shapes in every frame, where a TV's graphics chip keeps it as a picture.
  - *Tried here, with real key events on a TV-sized screen:* the whole shelf card by card and back, the cushion in focus whole on the
    screen at every step and the top of the home saying what it is; OK on a game beyond the first screenful; down to the other
    shelf and the buttons and back, each row where it was left; twelve presses with no pause between them; a second shelf of only
    three games; one of us changing colour while the home is up; a game played while away. **Not tried on the TV:** how it looks
    there from the couch (the threads' thickness above all), and that the box's graphics chip keeps up while a shelf slides.
  - Also: what phone and TV tell each other about versions is now the version at which the two last had to be updated together
    (`KeyLink.TOGETHER_SINCE`, still 1.9.4), so a release like this one, which changes only the TV, does not send anyone to update
    the phone.
- **1.9.6: the TV fitted to our TV, the time of day, a cursor of our choice, a game with the whole screen, drags that work.**
  From the first evening with 1.9.5:
  - *The picture fitted to the TV.* Our TV crops the edges of the picture (most do, a little), and parts of the screens were cut off.
    "התאמה לטלוויזיה" (a button at the foot of the home, and one in a game's dock) opens the TV's own settings, all with the remote:
    the picture's size (100% down to 85%: the whole picture, writing and all, is drawn smaller with a margin around it, until four red
    corners show whole) and the size of the writing and the buttons (85% to 125%). Both are a change of scale, so no screen is laid
    out differently; both are kept on the TV (`TvLayer`, `TvSettingsPanel`). Touches the cursor makes land where the picture is.
  - *The time of day* in a game's frame, on the TV and on the phone, instead of how long the game has been open (`Clock`).
  - *The cursor.* Smaller (30 dp instead of about 40), three sizes, and five shapes: a paw, a mouse, a fish, a ball of yarn, and a
    plain arrow in the interface's colour (the first of us's colour). Each points with a small red tip at its top left, where the tap
    lands, and lies below and to the right of it: nothing pointed at is covered any more (`CursorArt`).
  - *A game with the whole screen.* A button in the dock puts the frame away; Back brings it back (and only the next Back leaves
    the game). It is kept for that game.
  - *Dragging and scrolling.* The touches the cursor makes are now a finger's (a finger tool, pressure, a fingertip's size): until
    now they were Android's simplest touch, whose tool is "unknown", and some games' drag code did not take it for a finger. On
    the phone's touchpad a finger held still for a moment grabs (the phone buzzes), moving it drags, lifting it lets go, as on a
    phone; the button under the pad is still there. Two fingers scroll the page under the cursor directly (the wheel used to be
    handed to the window, and on its way through the app's layers did not always reach the page), at a gentler pace. With the
    remote, the cursor held against the top or the bottom of the screen scrolls the page on, faster the longer it is held.
- **1.9.7: the score saved with one button, "played" by itself, and points that make sense.**
  From the same evening:
  - *A button for the score, when the score is on the screen.* When a game shows its end screen (its own "share" or "copy" button
    is on the screen), the dock's "save the result" button turns pink and takes the remote's focus, the frame comes back if it was
    put away, and a word at the top says what to do. One OK presses the game's own share button for us (a tap, as a finger would),
    catches what the game copies, and opens the result sheet filled in; one more OK saves. The phone that is the TV's keyboard
    shows a red "the game is over: save the result on the TV" button at the same moment, which does the same. The end is told by a
    small script in the game's page that reads only the labels of buttons and links (share / copy, in English or Hebrew) and where
    they are, never the game's content or its answers (`ResultWatch`). No screenshot, no pasting.
  - *The result sheet on the TV* is made for the remote: what the game handed over is shown to read; the number by hand has − and +
    (minutes and seconds apart for a time) and starts from what was read, so a wrong reading is mended with a press or two; digits
    typed on the phone go into the number and Enter saves; the arrows stay inside the sheet while it is up, and the focus starts
    on "save". It stands whole on the screen, in two columns.
  - *Played, by itself.* A game we play together that stays open half a minute (counted only while the app is in front, never
    while signing in) is marked as played this evening, with a word at the top. The "played" button still unmarks it, and a mark
    or result given by hand is never touched.
  - *Points that make sense.* A new kind of result, "points, more is better", for the games that end with a score. A score out of
    a top (8/10, 4,230/5,000: commas and decimals are read) is worth its share of 10 couch points, rounded, never less than 1; a
    score with no top is worth 10 when it is our best, 7 within a fifth of the best, 5 below. Krillion both ways, Dialed and its
    sound game, Catfishing, the Daily Dozen, Magnitudle's two games, TimeGuessr, Thrice, Chrono Trivia, Cinematrix and More or
    Less are scored this way now; until now they could only be marked as played. The hub on the phone counts the same way
    (checked against the app's rules in a browser, `tools/hub_settings_check.cjs`).
  - *A little celebration* on the TV after saving, for three seconds: the couch points the result brought, a word that fits (a
    bullseye 🎯 for a full score, a new record for our fastest time, "tomorrow is ours" for a miss) and the evening's total so far.
  - Phone and TV now tell each other about the end of a game (`KeyLink`: `result` in "screen", and "save-result"), so **both apps
    must be updated together** this time (`TOGETHER_SINCE` 31); an older phone with the new TV, or the other way round, is told so.
- **1.9.8: touch mode, the TV's game on the phone to touch.** From the same evening: a game built on dragging (Dialed) was awkward
  from the couch, and easy on the phone, by touch.
  - *Touch mode.* Keyboard mode on the phone has a third choice at the top: "משחק | מקלדת | מגע". In "מגע" the phone shows the game
    that is open on the TV, several times a second, and a finger on that picture is a finger on the game at the same spot: put
    down, dragged, lifted, and a swipe scrolls, as on a phone. The TV draws a ring where the finger is (for whoever watches the TV),
    and its paw stays away meanwhile.
  - *By itself for the games played by dragging*: Dialed and its sound game, Pips, Disorderly, Magnitudle's two games, TimeGuessr,
    Cutle and Coffee Golf (`"touch": true` in the catalogue). When the TV opens one, the phone turns to touch; when it opens a game
    played with keys, back to the keyboard. Either can be chosen by hand at any time.
  - *How the picture travels.* The TV copies the game's page from its own screen (PixelCopy: what the graphics chip drew, a game's
    canvas included), as wide as the phone draws it (at most 960 pixels), as a JPEG, sealed with AES-256-GCM under a key both
    sides make from the pairing token and the connection's own nonce (`FrameCrypto`), for that phone only. One picture at a time:
    the next goes only after the phone has drawn the last, so a slow moment never piles pictures up. The box spends at most about a
    third of its time on them (a picture that cost 60 ms waits 180 ms for the next; at most about nine a second); a page standing
    still is pictured a couple of times a second, and sent only when it changed. No picture is kept anywhere.
  - So the TV no longer sends back only the screen's name: in touch mode, while the phone shows it and asks for it, it sends
    pictures of the game's page, sealed for that phone. Nothing a page holds (its text, its cookies, what is typed into it) leaves
    the TV.
  - Both apps must be updated together again (`TOGETHER_SINCE` 32).
  - *Tried here:* the link end to end over a real socket (a picture as wide as asked, opened whole on the phone, the next only after
    the first was drawn, the finger coming back, the game closing, touch mode ending); the sealing (another key, another number, a
    changed byte: refused); the finger on the page (shares of the page to its pixels, a move whose touch never came down dropped, a
    second touch lifting the first, the phone leaving mid-drag); the phone's screen (touch by itself and back, by hand, a picture
    drawn and acknowledged, a drag sent as shares of the picture). **Not tried on the devices:** how fast the pictures come over our
    Wi-Fi, what they cost the box while a game plays (its sound above all), and how a drag in Dialed feels from the phone.
- **1.9.9: each of us on our own phone, and notes left in games.** From the same evening:
  - *Who holds this phone.* The first time keyboard mode reaches the TV, the phone asks "מי מחזיק את הטלפון הזה?": the first of us
    or the second. From then on a chip at the top (the person's piece and name) opens the same dialog: the name, the shape (six)
    and the colour (eight). It is kept on the phone and sent to the TV, which keeps it and hands it to the other phone: each of us
    changes their own look from their own phone. Every copy of the state (the TV's, each phone's) keeps the newer look of each of us,
    person by person, by when it last changed (a change made in the hub's settings is stamped too) (`Couple`, `CoupleBook`).
  - *Both phones at once*, as before (up to four), typing and touching into the same screen with one cursor; the TV now names each
    connected phone by who holds it.
  - *Notes.* "✉️" at the top of keyboard mode writes a note for the other one: a few words, a small drawing in the writer's colour,
    and when it shows: after a game (the one open on the TV is chosen to start with), or on the TV's home. It goes to the TV, or
    waits on the phone until the TV is reached (the ✉️ counts the ones waiting). On the TV a note for a game shows after that
    game's result is saved and celebrated, or on the home once the game was played after the note was written (without a result);
    a note for no game shows on the home. A paper note, a little askew, says who it is from and for whom; one OK ("❤️ קראנו") puts
    it away for good. The TV keeps the latest 60 (`Notes`, `NoteCard`).
  - Both apps must be updated together again (`TOGETHER_SINCE` 33).
  - *Tried here:* over a real socket, the newer look winning both ways (the TV took Roni's newer one, the phone took the TV's newer
    Steve, a later change going at once, the TV naming the phone by who holds it) and a note written away from the TV waiting on
    the phone, then arriving once, from whoever holds that phone; the TV's home showing a note and putting it away with one OK (its
    button has the focus, though the home keeps a focus of its own); a note after a game's celebration; the phone's dialogs (asked
    once and saved; a note with words, a drawing and the game on the TV). **Not tried on the devices:** two phones connected to the
    box at once (allowed, but only one ever was), and how a note reads from the couch.
- **1.9.10: Dialed light enough for the box, Haaretz's games by their own links and scored, zoom in touch mode, no grey box.**
  From the first evening with 1.9.9 (the picture's size: right; the paw: right; touch mode: to keep):
  - *Dialed froze the TV in its second game* ("the game froze the picture, so we closed it": the app's guard, after four seconds of
    a frozen picture). Dialed blurs dozens of things, one by 70 px over more than the whole screen, frosts the glass behind its
    labels, and animates blurs; that is what a TV box's graphics chip cannot draw. On the TV the app now takes the blurs and the
    frosted glass out of Dialed as it draws: its style sheets come from another site, which a page's script may not read, so the
    blurred elements are found by how they are drawn (and as each animation starts) and drawn without the blur, and a rule of the
    app's own takes the frosted glass away. Colours, sizes, motion and the game stay; a blur that keeps something locked stays.
    Checked in Chromium on a page made like Dialed's (`tools/light_check.cjs`) and on Dialed itself (its first screen looks the
    same). How many blurs went is written in the report (ⓘ). `display_profiles.json`: `"tvLight": true` (`DisplayScript.LIGHT_FX`).
  - *Haaretz's games all opened the games' main page.* Each now opens its own page: the links were found by searching the web (the
    site itself cannot be opened from where this is built). 5 אותיות, מה הקשר?, איזה שיר!, מי אני? and הגיונית have their game
    pages; סודוקו its own page; 20 שאלות the column's page. Where a game's link leads somewhere else, the report says so.
  - *Haaretz's results*, which are words, not a number to copy: when "save the result" finds nothing to copy, the end screen's short
    lines that speak of a result are read (from the page and the frames of the same site in it; not the article around the game,
    not what is hidden) and turned into numbers: tries ("פתרתם בניסיון השלישי", "ב-4 ניסיונות מתוך 6"), mistakes, hints, a time, a
    score out of something, and how we did against everyone ("טובים יותר מ-82% מהשחקנים", "בין ה-10% המהירים"). The sheet opens
    with them in the app's own words ("קראנו את התוצאה ממסך הסיום"), to be checked and saved; a game with nothing but a standing is
    scored by it (better than 82% is 8 of 10). Only the numbers are kept, never a line of the page (a lost game's screen may hold the
    answer). איזה שיר! and מי אני? are now scored by hints, סודוקו by time, 20 שאלות by the score. **The end screens were not seen**:
    the patterns are the common ways of saying these things, and the first real ones may need a mend.
  - *Zoom in touch mode*: two fingers spread or pinch the picture and move it (they never touch the game); "+", "−" and "the whole
    game" buttons in its corner do the same with one hand. Zoomed in, the TV sends only that part of the page (`mirror.c`,
    `frame.c`), so it is as sharp as the TV's pixels allow; a tap still lands on the same spot of the game. An older TV sends the
    whole page, enlarged on the phone.
  - *A grey box behind one cushion*: Android puts its own focus on some button when a screen opens, which the remote never moves (the
    app takes the keys first), and drew its grey mark behind it. On the TV the app's buttons no longer wear Android's mark; the
    remote's focus (the red, a cushion's threads) is the only one (`tvTarget`, `TvPreviewTest.aCushionWithAndroidsOwnFocusLooksLikeAnyOther`).
- **1.10: who holds the phone and the notes on the phone's home, and more of us: friends who come often and guests of the evening.**
  From a remark on 1.9.10 (who holds the phone, and the notes, belong on the home, not only in keyboard mode):
  - *On the phone's home*, beside our pieces at the top: a user's mark by the piece of whoever holds this phone ("מי אני?" until it
    is said), which opens the same dialog keyboard mode has; and ✉️, which writes a note (with how many wait for the TV). On a phone
    the play/keyboard switch now has a row of its own under them, with its whole name again, and the logo no longer squeezes on a
    narrow phone (`tools/hub_modes_check.cjs` measures the header from 360 to 914 wide: everything fits, nothing overlaps).
  - *Notes written away from the TV*: a note waits on the phone, and the next time the app comes to the front on the phone it
    quietly reaches the TV it last knew, hands over what waits (notes, a changed look, someone added or removed), and lets go: at most
    20 seconds, nothing on the screen, nothing when the TV is off (`PhoneSync`). Keyboard mode still hands them over at once. A note
    can be for anyone here now, not only the other one of us: its sheet starts with who it is for.
  - *More of us.* In the who-holds-it dialog, "+ משתמש" adds someone who comes often (kept until removed) and "+ אורח" a guest of the
    evening (gone after it; the evening lasts till five in the morning, so a guest stays through a late game). Each has a name, a
    shape and a colour; the colour marks their piece only and never changes the app's look. They are in "מי פיצח?" (on the phone's
    home and on the result sheet, the TV's too), counted on the stats page, and named on the TV's notes; they can hold a phone, and
    be removed from the same dialog (the removal travels to the other devices like any change). Every device keeps them and the
    newest change wins, as for the two of us (`Couple.everyone`, `Couple.evening`, `couple.more` in `KeyLink`).
  - Both apps must be updated together again (`TOGETHER_SINCE` 34).
  - *Tried here:* the home's buttons in Chromium at five sizes, each opening its own dialog; a guest added from the home and saved as
    the one holding the phone; a note written with the TV nowhere, kept on the phone, and delivered over a real socket when the app
    came to the front, without keyboard mode; the others in "מי פיצח?" (the phone's home, the TV's sheet) and in the stats; a guest of
    another evening left out; the evening's end at five; a removal travelling. **Not tried on the devices:** the quiet hand-over when
    the app opens (how soon our Wi-Fi finds the box), and two phones held by a guest and one of us at once.
- **1.11: solo: a game played alone on one's own phone, sealed until the other plays it alone too, then the reveal.** "And if I
  play alone?": one game alone today (Dialed on the bus, say), the rest together in the evening.
  - *Together or alone?* On the phone, when a game opens, a sheet asks once a game and a day: "🛋️ ביחד" (the couch's game and
    points, as always) or "⚔️ לבד". When someone already played it alone today it says so ("סטיב כבר שיחק את זה היום לבד") and
    "alone" takes the challenge. "שיחקתי כבר במקום אחר (מחשב)" types a result in without opening the game. Who holds the phone is
    asked first when the phone does not know. The TV never asks: the TV is together.
  - *Alone, in one's own corner.* The game opens in the person's own browser profile (the LinkedIn one for the two of us, a new one for
    anyone else), not the couch's: a puzzle kept by a shared login (NYT) stays open for the evening, and the shared streak with it.
    The strip's tick says "שוחק לבד"; the result sheet is one person's ("⚔️ Wordle · לבד", no "who cracked it"); after a while in
    the game it is marked played alone by itself. Solo results never touch the couch's points or "played tonight".
  - *Sealed, then revealed.* Saved alone, the result goes into an envelope: "✉️ נחתם במעטפה", and whom it waits for. Nobody else sees
    how it went (on the other phone: "סטיב שיחק לבד · ✉️") until they have played the same day's game alone too, with a result. Then
    the reveal: the envelopes open one after the other, and the crown goes to whoever did best (fewer guesses, mistakes, hints or
    seconds; more points or words; not solved loses), or stays in the middle. It happens wherever the second result is saved; if the
    other's result arrives while the card is up, it opens there. The TV's home reveals each game both played alone today, once, when
    it is turned on. The stats page has "⚔️ לבד · ראש בראש החודש": the month's crowns and the last five.
  - *"רוני כבר שיחק היום ב-Bandle — מה איתך?"* Each phone tells the other through an ntfy topic (ntfy.sh, free, public): one message
    per change with the sides of whoever holds the phone, of today and yesterday, sealed with AES-256-GCM under a key the phones share.
    The TV makes the topic and the key once and hands them to each paired phone over the home network, sealed under that connection's
    own key (`relay` in KeyLink). The server sees that messages come and go on a topic nobody could guess, never what they say; a
    message under another key, or one phone writing another person's side, is dropped. A phone reads the topic when the app comes to
    the front, after a game alone, and by itself every quarter of an hour (Android's floor, `SoloJob`; only with a network): someone
    else's game alone that this phone's person has not played becomes a notification ("⚔️ רוני כבר שיחק היום ב-Bandle", "מה איתך?
    התוצאה סגורה במעטפה עד המשחק שלך."), and the other result of a game both played becomes "⚔️ חשיפה ב-Bandle!"; each said once. The
    phone asks whether it may notify the first time a game is played alone; the home's banner offers "🔔" when it may not. On the
    home: "⚔️ סטיב כבר שיחק היום ב-Bandle. מה איתך?" with "לשחק לבד" (no question), and "⚔️ חשיפה ב-Bandle!" with "לגלות".
    The relay keeps a message twelve hours, so a phone with a game alone today says it again after six. The TV reads the topic while
    it is in front (every five minutes), for its reveals.
  - *A game played on a computer:* copy its share text there and send it to yourself (WhatsApp Web, say); on the phone, share it to
    "על הספה שלנו": the sheet asks "ביחד או לבד?" too. Or "שיחקתי כבר במקום אחר" and type the result.
  - Both apps must be updated together (`TOGETHER_SINCE` 36): the TV hands out the relay.
  - *Tried here:* two phones and a TV through an ntfy topic in memory (Steve's game alone reaching Roni's phone as a challenge, said
    once; Roni's result reaching Steve's as the reveal; a box under another key and a forged side dropped; the TV keeping both and
    telling nobody; what could not be sent waiting, and today's sides said again before the relay lets them go), the client against a
    real HTTP server shaped like ntfy, the TV handing a paired phone the relay, the notifications, the background job; the sheet
    (asked once; who holds the phone first; the challenge; elsewhere), a result saved alone and sealed and the envelope opening when
    the other's comes in, a result shared from elsewhere, the TV's reveal (once); the home's banner, sealed row, reveal and crowns in
    Chromium (`tools/hub_solo_check.cjs`). **Not tried on the devices:** ntfy.sh itself (not reachable from where this is built), and
    how often a sleeping phone really looks (Xiaomi phones may need the app allowed to start by itself).
- **1.11.1 (the phone only): a photo of the computer's screen, for a game played there.** In "שיחקתי כבר במקום אחר (מחשב)":
  "📷 לצלם את המסך" (the phone's own camera app writes the photo into the app's cache; the app never uses the camera itself) or
  "🖼️ תמונה מהגלריה" (a screenshot sent to the phone, say). The picture is read on the phone (ML Kit, Google Play services' own
  model, downloaded with the app; no picture leaves the phone), only the line that holds the result is kept ("3/6", "Time 1:42",
  "41.23/50", "2 mistakes"; read across two lines when the screen splits it; never a date; the computer's clock alone on a line last),
  and the photo is deleted at once. The sheet says "📷 קראנו מהתמונה. כדאי לבדוק." over what it understood, or "לא מצאנו תוצאה
  בתמונה" and the number is typed as always. It reads English and numbers, not Hebrew letters: on Haaretz's games only the numbers
  come through, so their result is typed. The TV takes no photos (`PhotoText`, the phone's `FlavorText`).
  - *Tried here:* the line picker on screens made of lines (with the clock and a date, a split score, a page with no result), and the
    flow with a stand-in reader (the camera app asked for the app's own file, the result in the sheet, saved alone, the photo gone; a
    photo with no result, then typed). **Not tried on the devices:** ML Kit itself (it runs in Google Play services), and how well a
    photo of a screen reads (glare, an angle).
- **1.11.2: Haaretz's "מי אני?" share understood.** Its share is "כל הכבוד! הצלחת לזהות את הדמות תוך 6 צעדים" and the game's
  link: it named no game the app knew, and counted in steps, a word no reader knew. A share that names no game is now matched by the
  game's link in it (ours or the catalogue's; the same page however the link is dressed, or by the page's id), and steps are read as
  the game's hints ("6 צעדים" is 6, "צעד אחד" is 1), by the app, the home's page and the end-screen reader alike (`GameDetector`,
  `Scoring`, `HebrewResult`, `ResultWatch`). Tried here with that very share, through the share target to a saved result.
- **The per-game check on the box**: when a game is ready, how long after it was chosen; while the phone types, how fast each key shows
  on the page (from its keydown to the frame after the page handled it, measured in the page, times only, never which key) and how long
  keys waited for the TV app itself; all summed up in the flight recorder (ⓘ) when the typing pauses, e.g. "typing on krillion.io: 24
  keys: half on the screen within 48 ms, 9 in 10 within 180 ms, the slowest 300 ms". That is how the games that play well on the TV will
  be told from the ones better played from the phone.

**Install** (once): on the Mi Box, Settings, Device Preferences, About, press Build seven times; then Developer options, turn on
USB debugging (and network debugging). From a computer on the same network:

```sh
adb connect <the Mi Box's IP address>:5555
adb install -r app-tv-debug.apk
```

Without a computer: put the APK on a USB stick and open it with a file manager app from the Play Store, or use "Send Files to TV".
Later versions install over the old one (same key), so the logins stay.

**Use the release build on the box.** `assembleTvRelease` is the same app with R8 shrinking (under 3 MB instead of 15) and no debug
overhead, signed with the same committed key, so it installs over a debug build and keeps the logins. The debug build is for development;
on a weak TV box it is noticeably slower to start and to scroll.

**The remote: two modes, always visible.** A dock on the left edge of every screen holds the **paw**, the **keyboard** and **ⓘ**
(information to photograph when something goes wrong); in a game it also holds everything about the game's frame (below).

- **Navigate (the default).** The arrows move the focus from button to button, in the direction pressed, like a TV app moves between
  its tiles: through the app's own buttons, and *into the pages* too: the hub's buttons, the games' buttons, links and fields (a small
  script finds what can be pressed; it only reads where things are, never what they say). The focused button itself **takes the colour**
  (in the hub it turns red with cream letters; on a game's page a red tint in the element's own shape; never a box around it). **OK**
  presses it (as a real tap at its centre, so every game gets the touches it expects). The page **glides** to keep the focus in view: in
  a long list (the hub's games) the focus stays a little above the middle and the list moves one row per press, like an Android TV
  list; the next thing below the edge of the screen is reached directly; across a long stretch with nothing to press, each press scrolls
  about half a screen so it can be read. Holding an arrow walks on at a steady pace. Sideways stays on the same line. At the end of the
  page Left goes to the dock, and back.
- **Paw (the mouse), only when needed.** Press the paw button (a dark circle with a red paw while it is on, and a message at the
  top says which mode you are in): the arrows now move a free paw print, slowly at first and faster the longer an arrow is held,
  **OK** taps where it is, **hold OK** puts the finger down (the paw turns red) so the arrows drag, hold OK again to lift. Use it for
  games with nothing to select (a canvas, a grid you swipe across). If a page has nothing the highlight can land on, the app says so
  and points at the paw. The paw button switches back.
- **Back** and **Home** are the system's. The Mi Box remote has no Menu key, so the menu is the ☰ button in the dock.

**The dock in a game (one frame, not bars and panels).** On a TV there are no bars above and below the game and no side panels. The
whole frame is the dock at the left, and the game keeps the middle: the clock with the logo, the evening's score and the two of
us; **−** and **+** (zoom, kept per site); a phone drawn upright or on its side (phone-shaped or wide); **▣ / ◧** (the frame itself:
full, or slim with only the buttons as icons; remembered); the paw; the keyboard; ☰, ↩ and ⓘ; and, with words, "result" and "we
played". Every button says what it is at the foot of the dock while it is focused.

**The keyboard.** Only the keyboard button in the dock opens it (in 1.4 it also opened by itself whenever the window got focus back,
which was the "keyboard for no reason"); OK on a text field selects the field and says where the keyboard button is. It slides up
from the bottom and types into the field that was selected. It is small: four rows and a slim header that shows what you typed, about a third of the screen, and the game
area shrinks to make room, so you still see the game while you type. English, Hebrew and symbols (digits are on the symbols
layout); Shift makes one capital; the arrows move a highlight, OK types, **Back** or ✕ closes it. A password field shows dots. Letters
go in as real key presses (a game that listens for keys, like Wordle, just works); Hebrew and symbols are inserted as text. The
system keyboard is kept away so the two never fight. Sign-in works the same way: the field, OK, ⌨, type, Back, then "Sign in".

**Sound and video** play as on the phone, and a game's full-screen video fills the screen (Back leaves it).

What cannot be checked without the box: how the focus moves around real game pages (it is tested in Chromium on sample pages, a list like the hub's, the hub itself, and a heavy page with the CPU slowed six times; not on every game), how fast the paw feels, whether the keyboard reaches the page on this WebView, and whether
the WebView on the Mi Box is recent enough for the games. The Mi Box's own WebView version matters most (Settings, Apps, show system
apps, Android System WebView): the oldest ones cannot run some modern pages.

**Starting up, freezes and crashes.** On the TV the splash is not animated: the system's own starting window shows the cats at
once, and the hub fades in when it is ready. The hub runs in a lighter mode on the TV (no blurs, shadows or long animations), and
nothing runs while nothing is pressed (1.3 and 1.4 redrew the focus ring on every frame, which kept a weak box busy all the time).

1.5 makes sure every kind of failure leaves a trace that can be read on the TV itself, without a computer:

- **A flight recorder** (`diag/Flight.kt`): the last few hundred things the app did (screens opened and closed, pages loaded, the page's
  process ending, memory every minute and whenever Android says memory is short), written to a file as they happen. It also keeps whether
  the app was in front when its process ended.
- **A watchdog** (`diag/Watchdog.kt`): a thread that notices within 2.5 seconds when the main thread stops answering (the app looks
  frozen; Android offers to close it after about five) and writes down exactly where it is stuck, before the app can be closed.
- **On the next start**, a Java crash shows its report (as in 1.4), and so does a run that ended while in use without one (frozen and
  closed by the system, a crash inside the WebView's own code, out of memory): "בפעם הקודמת האפליקציה נעצרה באמצע", with what happened
  just before, newest first.
- **ⓘ in the dock** opens the same information at any moment, natively, so it works even when a page is stuck: versions, memory, the
  last start, the last crash, how the previous run ended, and what happened lately. Up and Down scroll it, Back closes it. The hub's
  Settings, "על האפליקציה", shows the same text.
- The remote can no longer get stuck: each screen has its own WebView (after a game was closed, the hub's arrows went to the game's dead
  WebView and stopped answering), a question to a page that does not answer in 1.2 seconds is given up, a mistake while handling a key is
  written down instead of crashing, and Back always works even if the hub does not answer.
- A game whose page process dies is reloaded once; if it dies again within two minutes (most likely the box out of memory) the player
  stops and says so, instead of reloading forever.

**Games that did not open (1.8).** On a Xiaomi TV (Android 11, WebView 158) in November 2026 no game opened: the report showed the main
thread stuck inside the WebView's `getOrCreateProfile`, called while the player was being built, and Android closed the frozen app. The
most likely cause was the app itself: 1.5 to 1.7 saved the profile's cookies from a background thread whenever a game was left, and that
call into the same profile code could hold it while the next game asked for its profile. 1.8:

- saves cookies only after a sign-in (the WebView saves them on its own anyway), and only on the main thread;
- puts a safety net around every call into the profile code: a mark on disk before, removed after. If the WebView never comes back, the
  mark is still there at the next start (and the flight recorder holds the frozen stack; a slow call that did come back does not count):
  separate logins are then turned off on this device, every site uses the app's single default profile (so each site needs one more
  sign-in), and the games open. They come back by themselves when the WebView is updated, or by hand with "לנסות שוב" in the accounts
  screen, which also says why they are off (each freeze is acted on once, so the same old freeze does not turn them off again);
- shows the app's own exit (not the page process's "isolated not needed") in the report, with the frozen main thread's stack from
  Android's own record, newest entries first and each stack in its own order.

**Standby, and a game that holds the picture (1.8.1).** The next report (23 November 2026) showed the 1.8 fix working (separate
logins switched themselves off, Krillion's page loaded) and a different freeze: about 2.5 seconds after the page loaded, the app's main
thread waited 20 seconds for the screen to take a frame (`HardwareRenderer.nSyncAndDrawFrame`), and it let go only when the box was
being turned off. The box then did not wake from standby without unplugging, as it has done since the TV app was installed. Android kept
no record of the app ending, so the app was idle in the background when the power was cut; whether it has anything to do with the wake
problem cannot be seen from inside it. 1.8.1 takes the app out of it as far as an app can:

- **Standby** (`diag/Standby.kt`): when the screen goes off, the app closes the game (and any sign-in page), sends itself behind the
  box's home screen (so the box wakes to its own home screen, not to a page) and stops listening for the phone at once. Screen off,
  screen on, the screensaver, shutdown and every change of the display's state or mode are written to the flight recorder.
- **A game that holds the picture** (`diag/DrawGuard.kt`): after 4 seconds of the main thread waiting for a frame inside a game, the
  watchdog's own thread closes the game (in the report, the window going away is what let go); if the app is still stuck 10 seconds
  in, it ends itself, so the box is never left holding a frozen picture. A message says so once the app is free.
- **The report**: Android's records of how the app ended are matched to the run they belong to (the 23 November report showed an older
  run's freeze as the previous run's end); a run with no record says the box was restarted or unplugged with the app still there; a line
  with the screen's mode; "typing began" when the phone starts typing; and, a few seconds after a game loads, how many videos (and how
  many playing), canvases and frames the page has (counts only, nothing else is read). The watchdog no longer reports a freeze when it
  was itself kept from running (the 2.5-second "freeze" of an idle main thread at start-up in that report).

**1.8.2: no more "light drawing", and no file writes on the main thread.** 1.8.1 also drew a game that had frozen the picture
"the light way" (a software layer: the page painted by the processor instead of the graphics chip), and marked Krillion so from the
freeze already in the report. On the box's processor that painting runs on the app's main thread and took seconds per frame: Krillion
took typing but answered so slowly it could not be played, and even the game menu took three to four seconds to appear (the next
report). 1.8.2 removes it and clears its marks. The same report showed the main thread stuck for 2.5 seconds opening a small file
(the flight recorder's note of which screen is in front), so the box's storage can be very slow at times: every write of the flight
recorder and of the start-up timings now happens on the recorder's own thread (only a crash still writes at once). The report also
gains what a white page needs: the player writes down when it refused to follow a page to another site, when a page did not load or
answered with an HTTP error, and certificate problems; and the page probe adds how many elements the page has and how tall it is (a
page that stays white shows a handful of elements and no height).

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

## The phone as the TV's keyboard (1.6; sideways, with a keyboard of its own, since 1.8)

Typing with a remote is slow, and games like Krillion are about typing fast. With both apps installed, the phone becomes the TV
app's keyboard:

- **On the phone**, the hub's header has a switch: **משחק** (play on the phone, as always) and **מקלדת לטלוויזיה** (keyboard mode).
  A long press on the app's icon has "מקלדת לטלוויזיה" too. "משחק" at the top of keyboard mode goes back.
- **Keyboard mode turns the phone on its side, and the whole screen is the keyboard** (1.8): a keyboard drawn by the app, in the app's
  own look. There is no line to type into on the phone: what is typed shows up on the TV, in whatever the TV shows (the game in front,
  or a field of the hub; the settings' links are much easier this way). The phone's own keyboard never opens, and the system bars hide.
- **The keys**: Esc and the digits; the letters as on a US keyboard, or Hebrew as on an Israeli one, or symbols ("עב", "EN", "123",
  "ABC" switch between them); Shift (once for one capital, twice to stay on, a third time off); a wide space bar; and on the right
  **⌫** (hold to keep deleting), a tall **Enter** with the cat's paw that comes down on it at every press, and the four **arrows** (held
  down, the game sees the key held, so a game that moves while an arrow is held works). A key goes the moment the finger touches it, with
  a light tick. English letters, digits and punctuation arrive as real key presses (a desktop game that listens for keys gets them);
  Hebrew is inserted into the field. When typing starts and nothing on the page has the keyboard's focus, the TV gives it to the field
  the remote's highlight is on (or was on last).
- **The first time**, the TV shows a card with four digits ("טלפון רוצה להקליד כאן"); type them on the phone's number pad. After that
  they know each other and connect by themselves whenever keyboard mode opens and the TV app is in front.
- **The paw's touchpad**, for the few places the remote does not reach (most of the app is easier with the remote): a dark tab with a
  red paw (it waves now and then) sticks out of the right edge. **Pull it** to the left: a striped cat's arm stretches out behind it,
  the touchpad (a pink cushion with stitching) comes in after it, and the keyboard gets narrower to make room, as far as it is pulled.
  Let go past a third of the way (or flick it) and it opens; less, and it springs back. Touching the tab opens or closes it too; push
  it back, or press ✕, and the whole screen is the keyboard's again. While it is out the TV shows the paw (the same paw as the remote's,
  so the dock's paw button lights up): one finger moves it (faster swipes go further), a short touch taps where it is (a paw print
  shows where you touched), two fingers scroll the page under it, "לחיצה" taps, "גרירה" holds the finger down so moves drag (touch it
  again to let go). Putting the pad away hides the paw again, unless the remote had turned it on.
- **Touch** (1.9.8): the third choice at the top, "מגע", shows the game that is open on the TV, and a finger on it touches the game
  there (see 1.9.8 above). It comes up by itself for the games played by dragging.
- **On the TV**, a small red phone at the top of the dock shows that a phone is connected.

How it works: the TV app listens on the home network while it is in front (port 47123, announced as `_couchkeys._tcp`), and the phone finds
it there, or where it found it last time, or at an address typed by hand (the TV shows its address under ⓘ). Only devices on the home
network can connect, only a paired phone can type (a random token from the pairing; six wrong codes close pairing for five minutes), at most
at a human pace, and the TV sends back the name of the screen it shows and, in touch mode (1.9.8), pictures of the game's page sealed for
that phone alone. Android 17 asks once on the phone to allow "devices on
the local network". If they cannot see each other (a guest network that keeps devices apart, for one), the phone says so and offers to
type the TV's address.

Not checked on the devices (nothing here can run them): the connection between the Pixel and the Mi Box, how instant it feels, and how
the keyboard and the pull feel under a thumb. The link itself is tested end to end over a real socket (pairing with a wrong and a right
code, typing, keys held, coming back without a code); the keyboard is tested on a landscape phone screen in Robolectric (every layout,
Shift and caps lock, held arrows, the pull that springs back and the one that opens, the touchpad's moves, taps and scrolls).

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
| `app/src/main/java/app/couch/alhasapa/` | Kotlin: `MainActivity`, `hub/` (WebView host), `data/` (state, backups), `catalog/`, `scoring/`, `bridge/` (hub to native), `player/` and `accounts/` (the isolated browser), `display/` (TV profiles), `results/` (capture, sheet, share target), `dnd/`, `tv/` (the remote's focus navigation, the paw, the keyboard, the dock, full-screen video), `remote/` and `keyboard/` (the phone as the TV's keyboard: the link, pairing, keyboard mode), `diag/` (startup timings, crash report, flight recorder, main-thread watchdog, the information text), `ui/` (splash, loading cover, crash screen, theme) |
| `app/src/main/assets/hub/` | The prototype's web UI, `pieces.json`, bundled fonts and images |
| `app/src/main/res/font/` | IBM Plex Sans Hebrew for native screens (the hub has its own copy in `assets/hub/fonts/`) |
| `app/src/main/assets/games.json` | The game catalogue (sites and games). Normally you only change a game's `u` (its link), or edit it in the app ("הקישורים שלנו") |
| `app/src/main/assets/display_profiles.json` | How each site is shown on the TV (mode, zoom, banner CSS) |
| `tools/` | `make_illustration_masks.py` (illustration masks), `extract_catalog.mjs` (the catalogue as it first came from the prototype), `gen_scoring_vectors.mjs` (golden vectors recorded from the prototype's own scoring), `display_check.cjs`, `sound_check.cjs`, `capture_check.cjs`, `tvnav_check.cjs` and `hub_settings_check.cjs` (the injected scripts and the hub's settings page, run in real Chromium) |
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
