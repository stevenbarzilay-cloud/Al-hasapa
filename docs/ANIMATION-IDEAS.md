# Animation ideas: the splash's language across the app

The splash is three cats asleep on a pink couch, Z's floating up, the logo bouncing in. The idea: the cats are the app's
**state**, not decoration. Every animation below uses the same art and the same recolouring (the cats' couch and shirts already
follow Steve's and Roni's colours), a short duration (under 1.2 s), and is skipped completely when "Remove animations" is on.

| Where | Idea | Why it works |
| --- | --- | --- |
| Loading (hub, a game, sync) | The cats sleep, Z's rise; they stir when the page is ready | the loading screen stops being empty and says "almost" |
| Entering a game | The couch's TV frame grows to the screen's edges; back, the cushions settle | one continuous gesture from the hub to the game |
| Result saved | A red paw stamp lands on the row (the one red stamp of the design); one cat's ear twitches | quiet applause, no confetti |
| Everything played tonight | The cats curl up, the lamp dims one step | the evening has an end |
| A win in a head-to-head | The winner's cat sits up, the other stretches | the duel has a face |
| Streak | One more cat on the couch per day, up to three, then they swap places | the streak is something you can see |
| TV cursor | A paw instead of an arrow; pressing OK presses the paw, a small ripple | the cursor feels like part of the world |
| TV keyboard | Keys press down like cushions; the handle is a cat's tail hanging over the edge | the pull-up keyboard is discoverable and charming |
| Casting / DND nudge | The remote peeks out between the cushions (it is already in the art) | the nudge is a joke, not a warning |
| Several users (later) | Each person is a cat or a piece on the couch; "who is on the couch tonight" is a seat map | multi-user as a place, not a settings page |
| Sign in / sync | A cat carries a small card across to the other device's couch | accounts feel like furniture moving, not forms |
| Idle (TV) | The cats breathe slowly; after a while, one is gone and the remote is found | a screensaver with a story |

How to build it with what is already here: Compose animations over the existing illustrations (no new library), the masks
and colour layers from the illustration recolouring, `reduceMotion` already passed everywhere, one `Cat` composable with a few
states (asleep, stirring, sitting, stretching, curled) that every place above reuses. Phase 1: loading, result-saved stamp, and the
TV paw cursor. The rest follow the TV and several-users work.
