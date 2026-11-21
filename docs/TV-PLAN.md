# The Android TV app: plan

Goal: the games run **on the TV itself**, controlled with the remote, instead of casting the phone (which lags and sometimes
drops). Same look, same results and history as the phone app. This is a plan, not built yet; the questions at the end block
the parts that need a decision.

## Shape: one codebase, two flavors

`phone` (today's app) and `tv` (new), in the same repository and module, so the catalogue, scoring, results, display profiles,
state and the isolated player are shared and fixed once. Only what differs lives per flavor:

| | phone | tv |
| --- | --- | --- |
| launcher | normal icon | Leanback launcher intent and a 320 x 180 banner, no touchscreen required |
| screen | hub in both orientations, player sideways | landscape only, 10-foot sizes (text 24 sp and up, big targets) |
| control | touch | remote: D-pad cursor over the game, focus navigation over the UI |
| typing | system keyboard | slide-up keyboard from the bottom, driven by the remote |
| result capture | Share / Copy hook, share target | the Share / Copy hook only (there is no share sheet to receive from) |
| do-not-disturb chip | yes | no |
| applicationId | `app.couch.alhasapa` | `app.couch.alhasapa.tv` (both can exist, nothing clashes) |

## Remote control (no touch, no mouse)

- **Cursor over the game.** The D-pad moves a visible cursor (a paw, see ANIMATION-IDEAS.md) over the page; holding a direction
  speeds it up. OK is a tap at the cursor (a real down and up touch event handed to the WebView, so every game works, including
  canvas ones that have no focusable elements). Holding OK starts a drag; OK again drops it (drag to scroll, to swipe a Strands
  word). Page up / down keys, if the remote has them, scroll.
- **Focus over the UI.** The bars, the menu and the keyboard use ordinary focus navigation. Pushing the cursor past the top or
  bottom edge of the game hands the focus to the bar or the keyboard handle, and back again, so it feels like one surface.
- **Keys.** Back closes the keyboard, then the menu, then asks before leaving a game. Menu (or long OK on the bar) opens the menu.
  Play/Pause toggles cursor and focus mode. Nothing needs the colour buttons, but they can be mapped (zoom, keyboard).
- It must be possible to do everything with the arrow keys, OK and Back alone: that is the only part every remote has.

## The keyboard

- A handle (⌨) is always visible at the bottom edge; OK on it, or the cursor pushed down onto it, slides the keyboard up.
  It also slides up by itself when a text field in the page takes focus (detected from the page's focus events, not its content).
- Layouts: English, Hebrew (right to left), digits and symbols; Shift, Backspace, Enter, Space, and a "password" mode that
  masks the preview line.
- Each key is delivered as a real key event to the WebView (so a game that listens for key presses, like Wordle, just works);
  Hebrew letters and symbols that have no key code go in as typed characters.
- This is what makes **signing in with a password** possible on the TV: the sign-in forms are the real sites' forms, typed
  with this keyboard. Logins persist exactly as on the phone (own cookie store, survives restarts).

## Sound and video

The WebView plays audio and video already (autoplay is on for the music games). The TV flavor adds what a TV needs: full-screen
video (`onShowCustomView`), the Back key leaving full screen first, and audio focus handling when the app is in the background.
Widevine (DRM) comes with the system WebView; no game here needs it.

## Accounts and sync across devices

What it can do, honestly:

- **Synced:** the state document: history, "we played" ticks, results, the names, pieces (shapes and colours), the games on the
  shelf, personal links, display profiles. It is already one JSON document with a merge rule (newest entry wins per day and game;
  head-to-head sides merge side by side), which is exactly what a sync needs; the import/export merge is reused as is.
- **Not synced:** the logins to NYT, Haaretz and LinkedIn. Cookies belong to a device's browser and cannot be moved safely;
  each device signs in once. (On the TV, with the keyboard, that is a one-time job.)
- **Design:** an `AccountService` (email + password sign-up and sign-in, password reset, sign-out) and a `SyncService` (pull,
  merge, push, on app start, on resume and after a save, with a "last synced" line in the menu) behind interfaces, so the app
  works fully signed out and the backend can be swapped. Tokens live in the Android Keystore. No analytics, no ads.
- **Later (your list):** several users per account and avatars: a `profile` layer over the same state (each person has a name,
  a piece or avatar, a colour), the "who plays?" step already exists for head-to-head games and generalises to "who is on the
  couch tonight".

## Order of work

1. **TV flavor with cursor, keyboard, full-screen video, launcher** (no backend needed): **built in 1.2.0** for the Xiaomi Mi Box
   (API 26 and up). What changed from the plan: the cursor is one virtual finger that hands real touches to the whole window, so
   it works in the hub and the menus too; OK taps, holding OK latches a drag (a remote's centre button cannot be held while an arrow is
   pressed); the keyboard handle rises when the cursor is pushed to the bottom edge (a permanent handle sat over the page's own
   controls), and the keyboard also opens by itself when a field takes focus, from the menu, and on Play/Pause. The Mi Box remote has no Menu key,
   so the ☰ button is how the menu is reached.
2. **Accounts and sync** (needs the backend decision below).
3. **Several users and avatars, and the animation set** (ANIMATION-IDEAS.md).

## Risks, said plainly

- I cannot run a TV or an emulator in the build environment. The cursor's speed and feel, the keyboard and full-screen video can
  only be judged on your TV; I will ship small and ask for screenshots and impressions, like with the phone.
- Old Android TVs ship an old System WebView that may not update. The code already degrades (no per-profile logins, no capture)
  and says so, but a very old WebView can break a modern game page. Which TV it is matters.
- A password typed with a remote is slow; the plan is a fast keyboard, a clear "show/hide" for the preview, and saved logins
  that survive, so it is done once.

## Decisions (from the chat)

1. Backend for accounts and sync: leaning Supabase (the open question was whether it scales to more users: it does, see the chat).
   To start I need a Supabase project's URL and public (anon) key; I will provide the SQL to run in it.
2. The TV is a Xiaomi Mi Box (Android TV 8.1 or 9, so the TV app goes down to API 26).
