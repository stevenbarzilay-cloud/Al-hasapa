# "על הספה שלנו" — Android app for Pixel 8 (handoff prompt for Claude Code)

Paste everything below into Claude Code, from the root of a new, empty project folder that also contains the `couch-artifact/` folder from this package.

---

## Who this is for

Steve and his partner Roni play daily web puzzle games together in the evening. They play on Steve's **Google Pixel 8** and cast the phone screen to the TV with built-in casting (Chromecast-style screen mirroring). They mostly play **together**. LinkedIn games are the exception: each plays separately on his own account, and they compete.

We designed and validated a web prototype called **"על הספה שלנו"** ("On our couch"). It's in `couch-artifact/index.html`, with images in `couch-artifact/img/`. It is the **source of truth** for:
- visual language and copy (Hebrew, RTL)
- the game catalog
- the data model
- the scoring rules

Your job is to turn it into a personal Android app that fixes three real problems:

1. **Privacy on the TV.** Today the games run in Steve's personal Chrome, so tabs, history and notifications show up on the TV. The app must be its own isolated browser.
2. **Logins.** Steve has paid accounts on **The New York Times** and **Haaretz** (הארץ). LinkedIn games need each player's own account. Logins must persist inside the app.
3. **Layout on the TV.** They play with the phone in **landscape** (TV-shaped). Many game pages don't fit in landscape. See `couch-artifact/bug-haaretz-chain-*.png`: on Haaretz "השרשרת", landscape shows only the side bins and the board falls off-screen. The app needs per-site display control so every game fits the screen and reads well from the couch.

This is a single-user, sideloaded app, not for the Play Store. Optimize for a delightful personal tool over generality.

## Before you write code

1. Read `couch-artifact/index.html` end to end. The `<style>` block's `:root` comment explains the color roles. The script contains:
   - `GAMES` (the catalog)
   - `SITES`
   - `METRIC`
   - `parseResult()` (parses pasted share text)
   - `resultPts()` and `entryPts()` (team scoring)
   - `duelWinner()` (LinkedIn head-to-head)
   - the `state` shape
2. Look at the two bug screenshots.
3. Propose a short plan with milestones and the open questions you actually need answered. Then wait for my OK.

## Tech choices (defaults; push back if you have a strong reason)

- **Language and UI:** Kotlin, Android Studio project, Gradle KTS. Native shell in Jetpack Compose.
- **SDK:** minSdk 29, target the latest stable SDK. Test target: Pixel 8.
- **The hub screen:** reuse the prototype's web UI (HTML/CSS/JS) inside a local WebView served with `WebViewAssetLoader`. Do not rebuild it in Compose. This preserves the design exactly. Replace its storage layer with a JS↔native bridge.
  - The prototype persists via `localStorage` plus a `claude.use("db")` cloud doc. Remove the `claude.use` code. Keep one `state` JSON persisted natively, in DataStore or a single Room table holding the JSON.
- **The game player:** a separate native screen hosting a WebView for the external game sites.
- **Build output:** a debug APK. Include exact steps to install via Android Studio or `adb install`.

## Feature 1: Isolated game player with persistent logins

- One WebView profile dedicated to games. Configure it:
  - `CookieManager` accepting cookies and third-party cookies, with cookies flushed on pause.
  - DOM storage and JavaScript on.
  - Media playback without a user gesture where possible, because music games such as Bandle and Haaretz "איזה שיר!" need audio.
  - A file chooser is not needed.
- **Accounts screen** ("החשבונות שלנו"). List NYT, Haaretz and LinkedIn. Each row has a "להתחבר" button that opens that site's login page in the player, using the same cookie jar. Show a best-effort "מחובר" indicator by checking for the site's session cookies; mark it as best-effort in code.
- **Two LinkedIn profiles (Steve / Roni).** LinkedIn needs a different login per player. Use two separate WebView data directories or profiles; the AndroidX WebKit `Profile` API is fine if available, and if not, document the fallback. LinkedIn games open in the profile of the player whose turn it is. The play button asks "מי משחק?" with the red circle (Steve) and the pink triangle (Roni) pieces.
- **Google sign-in limitation.** Google blocks OAuth inside embedded WebViews. Do not spoof the user agent to get around this. Instead:
  - Recommend email and password login per site.
  - Add a per-site fallback, "לפתוח בכרום", that opens the game in a Chrome Custom Tab.
  - Explain this in the Accounts screen copy.
- **No paywall bypassing, no answer scraping, no automation of gameplay.** We only display pages Steve is entitled to and read the share text the player explicitly creates.
- **Navigation.** Keep navigation inside the player for the game's own domain. Open off-domain links (ads, articles) in a Custom Tab, or block them with a small toast. Back gesture goes back in the WebView, then exits to the hub.

## Feature 2: TV mode and per-site display profiles (the core of this app)

### Player chrome

- Lock the player to landscape (`sensorLandscape`). The hub supports both orientations.
- Immersive fullscreen: hide the system bars, and keep the screen on while playing.
- A slim, auto-hiding control strip at the screen edge, in the prototype's style:
  - back to hub
  - game name
  - "שיחקנו" / "תוצאה"
  - display settings ⚙

### Display profile

Each game URL pattern has a profile with these fields:
- `mode`, one of:
  - `"native"`: the page as is.
  - `"wide"`: emulate a desktop-width viewport, e.g. 1280 CSS px, scaled to fit. Use `useWideViewPort`, `loadWithOverviewMode` and a viewport-meta override injected at document start.
  - `"portrait-fit"`: render the page at a phone-portrait virtual viewport, e.g. 430×932 CSS px, scaled to fit the landscape screen's height and centered. This is the mode that fixes Haaretz השרשרת.
- `zoom`: a percentage.
- `injectCss`: hide site headers, cookie banners and "get the app" prompts. Fix `100vh` issues.
- `safeArea`: TV overscan padding, default 2.5%.
- `dark`: false, which forces light mode.

### Side panels in portrait-fit

In `portrait-fit`, the left and right pillarbox areas are **not wasted**. Use them as couch-styled panels:
- One side: the logo, the game name and an elapsed timer.
- The other side: tonight's progress (X מתוך Y, נקודות ספה), the two pieces, and a big "תוצאה" button.

Everything must be readable from 3 m away: minimum 20sp text in the panels.

### Live tuning and profile storage

- A live tuning sheet opens from ⚙ during play:
  - mode segmented control
  - zoom − / +
  - "לשמור לאתר הזה"
- Profiles are saved per domain plus path prefix.
- Ship sensible defaults in an editable `assets/display_profiles.json`:
  - Haaretz riddles: `portrait-fit`.
  - NYT games: `portrait-fit` for Wordle, Connections, Strands and Pips; `wide` for the Crossword.
  - LinkedIn games: `portrait-fit`.

### Acceptance

With the Pixel 8 in landscape:
- The entire Haaretz השרשרת board, both bins and the confirm button are visible without scrolling.
- Wordle's keyboard and grid are fully visible.
- Text on the TV is legible from the couch.

## Feature 3: Results, scoring and auto-capture

Port the prototype's scoring **exactly** (functions named above):

- **Together games: "נקודות ספה" (team points).**
  - Playing a game is worth 2 points.
  - A parsed result adds up to 10 more:
    - guesses: 1–2 tries is 10, scaling down to 1–2 at the last try; a fail is 0.
    - Connections mistakes: 0, 1, 2, 3 mistakes give 10, 7, 4, 2.
    - Strands hints: 0, 1, 2, 3+ hints give 10, 7, 5, 3.
    - time: a new personal best is 10, within 25% of the best is 7, otherwise 5.
  - "מי פיצח?" is **credit, not a win**: Steve, Roni or ביחד.
- **LinkedIn: head to head.** Each player pastes or captures his own result. The lower time wins; in Pinpoint, fewer guesses wins. Equal results are a tie.
- **Auto-capture (the magic part).** When a player taps a game's own Share button:
  - Inject a document-start script on game pages that wraps `navigator.share` and `navigator.clipboard.writeText`, and forward the text to native via `addJavascriptInterface`. Restrict this to the allowlisted game domains and validate the input.
  - Then the result sheet opens pre-filled. For LinkedIn, it's pre-assigned to the player whose profile is active.
  - Still call through to the original API, so normal sharing keeps working.
- **Android share target.** Register for `ACTION_SEND` with `text/plain`. If they share a result from another app or from Chrome, "על הספה" appears in the share sheet. Then:
  - Detect which game the text belongs to by its header ("Wordle", "Connections", "Queens #" …).
  - Ask "מי?" for duel games.
  - Save the result to today.
- Keep manual entry and "לא הצלחנו" as fallbacks, as in the prototype.

## Feature 4: Data

- Same JSON schema as the prototype's `state`:
  - `v`, `names`, `prefs`, `jokes`
  - `log[YYYY-MM-DD][gameId] = {w, r?, d?}`
- **Import:** the prototype has a "להעתיק גיבוי" button that copies `{app:"al-hasapa-shelanu", state}` to the clipboard. Add "לייבא גיבוי" in settings. It pastes that JSON, validates it and merges it with existing data, keeping the newer entry per day and game.
- **Export:** the same format, so we never lose history.
- **Catalog:** move `GAMES` and `SITES` into an editable `assets/games.json`. Steve will add **direct links for every game, especially Haaretz**. Today several Haaretz games point to the riddles hub (`https://www.haaretz.co.il/riddles`). Make the URL the only thing he has to change. Bonus: a settings screen to edit a game's URL in-app.
- No cloud sync for now. Design the storage layer so sync could be added later.

## Design rules (from the prototype; keep them)

- **Colors.** Cream `#F2EBDD` background, card `#F8F3E9`, ink `#30232D`, red `#D94E38`, pink `#E8A5B5`. Roles are fixed:
  - ink: text, rules, primary buttons
  - red: Steve's piece (circle), the evening count, the single stamp
  - pink: Roni's piece (triangle), warm surfaces
- **Type.** One readable UI face: IBM Plex Sans Hebrew. Bundle the font, no network fonts. The logo image `img/logo.webp` keeps its proportions.
- **Hierarchy everywhere:** the game first, then the action, then the small personal details.
- **Primary buttons.** One per area, with a small hard red shadow. Everything else is secondary text buttons.
- **Personal illustrations:** `couple.webp` on home, `cats.webp` at the end of the library and in empty states, `board.webp` in the LinkedIn duel stats. Give them room.
- **Motion.** Small press feedback, a hop for a selected piece, a drawn checkmark. Respect the system "remove animations" setting.
- **Hebrew, RTL, natural short microcopy.** Keep every string from the prototype. Put strings in `strings.xml` (he).
- **Touch targets.** At least 48dp in native UI.

## Non-goals

- Play Store release.
- iOS.
- Multi-user.
- Ads or analytics.
- Scraping answers.
- Bypassing paywalls or Google's WebView sign-in policy.

## Acceptance checklist

- [ ] Fresh install, then import the backup: history, names, jokes and scores match the prototype.
- [ ] Log in once to NYT and Haaretz. After force-stop and relaunch, still logged in. Premium games open.
- [ ] Two LinkedIn profiles: Steve and Roni each stay logged in to their own account.
- [ ] Haaretz השרשרת and NYT Wordle fit fully in landscape (portrait-fit, with side panels).
- [ ] Per-site display tweaks persist.
- [ ] Tapping Share in Wordle or Queens inside the player opens the result sheet pre-filled. The points or duel winner match the prototype's functions.
- [ ] Sharing text from another app via the Android share sheet lands in the right game.
- [ ] Nothing from Steve's personal Chrome is visible. While casting, a "נא לא להפריע" reminder chip shows once per evening and links to the DND settings.
- [ ] Hebrew RTL is correct everywhere. TalkBack labels exist for the pieces and buttons.

Work in small milestones, run the app after each one, and tell me exactly what to test on the phone.
