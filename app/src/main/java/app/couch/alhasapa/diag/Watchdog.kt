package app.couch.alhasapa.diag

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Watches the main thread from a thread of its own. If the main thread stops answering for [stallMs] (the app looks frozen; Android
 * offers to close it after about five seconds), it writes down where the main thread is stuck, right then, while the process is still
 * alive. While it stays stuck, [onStuck] hears about it at every look (the draw guard acts on that). When the thread is free again it
 * writes down how long the freeze lasted. Every [sampleEveryMs] it also writes how much memory there is, because a box that runs out of
 * memory closes the app without a word.
 *
 * One question at a time, timed from when it was asked: a watchdog that was itself kept from running (a busy box right after it starts)
 * finds its last question answered long ago and reports nothing. Until 1.8.1 it timed the main thread's last answer instead, and once
 * reported a 2.5 second "freeze" of a main thread that was idle.
 *
 * [check] is the whole logic and has no sleeping in it, so it can be tested with a paused main looper and a made-up clock.
 */
class Watchdog(
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val stallMs: Long = 2_500,
    private val onStall: (stuckMs: Long, where: Array<StackTraceElement>) -> Unit = { ms, at -> reportStall(ms, at) },
    private val onRecover: (stuckMs: Long) -> Unit = { ms -> Flight.log("$FREE after $ms ms") },
    private val sampleEveryMs: Long = 60_000,
    private val onSample: () -> Unit = {},
    /** At every look after [onStall] while the main thread is still stuck: for how long, and where it is now. */
    private val onStuck: (stuckMs: Long, where: Array<StackTraceElement>) -> Unit = { _, _ -> },
) {
    /** When the question now waiting for the main thread was asked; -1 when none is waiting. */
    @Volatile private var askedAt = -1L
    @Volatile private var answeredAt = clock()
    private var stuck = false
    private var stuckSince = 0L
    private var lastSample = clock()
    private val answer = Runnable { answeredAt = clock(); askedAt = -1L }

    /** One look: if the last question was answered, ask the next; if not, judge how long it has been waiting. */
    fun check() {
        val now = clock()
        val asked = askedAt
        if (asked < 0) {
            if (stuck) { stuck = false; onRecover(answeredAt - stuckSince) }
            askedAt = now
            main.post(answer)
        } else if (now - asked >= stallMs) {
            val at = main.looper.thread.stackTrace
            if (askedAt == asked) {                         // not answered while the stack was being read
                if (!stuck) { stuck = true; stuckSince = asked; onStall(now - asked, at) } else onStuck(now - asked, at)
            }
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
        /** How the flight recorder's lines about a freeze begin (read back by [Flight.freezes]; the same since 1.5). */
        const val STUCK = "MAIN THREAD STUCK"
        const val FREE = "main thread free again"

        fun reportStall(ms: Long, at: Array<StackTraceElement>) {
            val frames = at.take(14).joinToString("\n") { "    at $it" }
            val what = if (drawing(at)) " (waiting for the screen to take a frame)" else ""
            Flight.log("$STUCK for $ms ms$what, at:\n$frames")
            Flight.flushNow()           // the system may close the app any second now
        }

        /** The main thread is waiting for the drawing thread (and so for the graphics) to take the next frame. */
        fun drawing(at: Array<StackTraceElement>): Boolean = at.take(6).any { it.methodName.endsWith("yncAndDrawFrame") }
    }
}
