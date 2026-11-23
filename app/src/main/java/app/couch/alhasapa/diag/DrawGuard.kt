package app.couch.alhasapa.diag

import android.app.Activity

/**
 * A game that holds the TV's drawing stuck. On 23 November 2026, a few seconds after Krillion opened, the app's main thread waited
 * 20 seconds for the screen to take a frame; it let go only when the box was being turned off, and that box then did not wake
 * without unplugging. In the report it was the window going away that let go.
 *
 * So, on the TV, from the watchdog's own thread (the main thread is the one stuck): after [CLOSE_MS] of that inside a game, the game
 * is closed (finishing an activity is a message to the system, which may come from any thread; the system then takes the window
 * away), and only then written down (a file write can itself be slow on that box). If the app is still stuck [END_MS] into the
 * freeze, it ends itself, so the box is never left holding a frozen picture.
 *
 * (1.8.1 also drew the game's site without the graphics chip from then on. On the box's processor that made the game crawl, so 1.8.2
 * took it out.)
 *
 * [decide] is the whole policy and is pure, for the tests.
 */
class DrawGuard(
    /** The screen in front, and whether it is a game. */
    private val front: () -> Activity?,
    private val isGame: (Activity) -> Boolean,
    /** After the game was closed, on the main thread once it is free again: tell the people. */
    private val afterClose: () -> Unit,
    private val end: () -> Unit,
) {
    enum class Act { NONE, CLOSE, END }

    private var closed = false

    /** From the watchdog, at every look while the main thread is stuck. */
    fun onStuck(ms: Long, at: Array<StackTraceElement>) {
        val a = front()
        when (decide(ms, Watchdog.drawing(at), a != null && isGame(a), closed)) {
            Act.NONE -> {}
            Act.CLOSE -> {
                closed = true
                val closedOk = runCatching { a?.finish() }.isSuccess
                Flight.log("drawing stuck for $ms ms in a game: ${if (closedOk) "closed it" else "closing it failed"}")
                Flight.flush()
                afterClose()
            }
            Act.END -> {
                Flight.log("still stuck $ms ms into the freeze after closing the game: the app ends itself so the box can draw again")
                Flight.flushNow()                           // the process ends next: this one must be on disk first
                end()
            }
        }
    }

    /** The main thread is free again. */
    fun onRecover() { closed = false }

    companion object {
        const val CLOSE_MS = 4_000L
        const val END_MS = 10_000L

        fun decide(ms: Long, drawing: Boolean, inGame: Boolean, closed: Boolean): Act = when {
            closed && ms >= END_MS -> Act.END
            !closed && drawing && inGame && ms >= CLOSE_MS -> Act.CLOSE
            else -> Act.NONE
        }
    }
}
