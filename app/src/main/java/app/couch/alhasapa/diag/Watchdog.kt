package app.couch.alhasapa.diag

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Watches the main thread from a thread of its own. If the main thread stops answering for [stallMs] (the app looks frozen; Android
 * offers to close it after about five seconds), it writes down where the main thread is stuck, right then, while the process is still
 * alive. When the thread is free again it writes down how long the freeze lasted. Every [sampleEveryMs] it also writes how much memory
 * there is, because a box that runs out of memory closes the app without a word.
 *
 * [check] is the whole logic and has no sleeping in it, so it can be tested with a paused main looper and a made-up clock.
 */
class Watchdog(
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val stallMs: Long = 2_500,
    private val onStall: (stuckMs: Long, where: Array<StackTraceElement>) -> Unit = { ms, at -> reportStall(ms, at) },
    private val onRecover: (stuckMs: Long) -> Unit = { ms -> Flight.log("main thread free again after $ms ms") },
    private val sampleEveryMs: Long = 60_000,
    private val onSample: () -> Unit = {},
) {
    @Volatile private var lastAnswer = clock()
    private var stuck = false
    private var stuckSince = 0L
    private var lastSample = clock()
    private val answer = Runnable { lastAnswer = clock() }

    /** One look: ask the main thread to answer, and judge how long ago it last did. */
    fun check() {
        main.post(answer)
        val now = clock()
        val silent = now - lastAnswer
        if (silent >= stallMs) {
            if (!stuck) {
                stuck = true
                stuckSince = lastAnswer
                onStall(silent, main.looper.thread.stackTrace)
            }
        } else if (stuck) {
            stuck = false
            onRecover(lastAnswer - stuckSince)
        }
        if (now - lastSample >= sampleEveryMs) { lastSample = now; onSample() }
    }

    /** Runs [check] twice a second, for the life of the process. */
    fun start() {
        Thread({
            while (true) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                runCatching { check() }
            }
        }, "main-watchdog").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }.start()
    }

    companion object {
        fun reportStall(ms: Long, at: Array<StackTraceElement>) {
            val frames = at.take(14).joinToString("\n") { "    at $it" }
            Flight.log("MAIN THREAD STUCK for $ms ms, at:\n$frames")
            Flight.flushNow()           // the system may close the app any second now
        }
    }
}
