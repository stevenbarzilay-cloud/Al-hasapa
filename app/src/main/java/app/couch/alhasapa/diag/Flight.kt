package app.couch.alhasapa.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A flight recorder: the last few hundred things that happened, written to a file as they happen. A Java crash leaves a stack trace
 * (see [CrashReport]), but many endings leave nothing: the system closing an app that froze, a crash inside the WebView's own native code,
 * a box that ran out of memory. Those can only be read afterwards from what was written down before.
 *
 * It also keeps whether the app was in front when its process ended. If it was, the end was not the person's doing (nobody closes an
 * app from inside it on a TV), and the next start says so, with what happened just before.
 */
object Flight {
    private const val TAG = "AlHasapaFlight"
    private const val FILE = "flight.txt"
    private const val PREVIOUS = "flight-previous.txt"
    private const val SESSION = "session.txt"
    private const val RUN = "run.txt"
    private const val MAX_LINES = 300

    private val lines = ArrayDeque<String>()
    @Volatile private var dir: File? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "flight-recorder").apply { isDaemon = true } }
    private val writeQueued = AtomicBoolean(false)
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** What the previous run left behind, read once when this process started. */
    class Previous(
        /** The process ended while one of the app's screens was showing. */
        val endedInFront: Boolean,
        /** What was showing then (a screen's name) and when. */
        val lastSeen: String,
        val log: String,
    )

    @Volatile var previous: Previous? = null
        private set

    /**
     * When the previous run started (the wall clock, ms), or null when not known (before 1.8.1). Android's records of how the app's
     * processes ended that are older than this belong to earlier runs, not to [previous].
     */
    @Volatile var previousStart: Long? = null
        private set

    /** The run before [previous]: kept one start longer, so a freeze that was reported once still counts as evidence. */
    @Volatile var older: String = ""
        private set

    /** Once per process, as early as possible. */
    fun start(context: Context) {
        val d = context.filesDir
        dir = d
        val session = read(File(d, SESSION))
        val log = read(File(d, FILE))
        older = read(File(d, PREVIOUS)).orEmpty()
        previousStart = read(File(d, RUN))?.trim()?.toLongOrNull()
        previous = Previous(
            endedInFront = session?.startsWith("front") == true,
            lastSeen = session?.substringAfter(' ', "")?.trim().orEmpty(),
            log = log ?: older,
        )
        // Read now (the logic needs it), written on the recorder's own thread (after the reads, in order).
        val startedAt = System.currentTimeMillis()
        writer.execute {
            runCatching { File(d, RUN).writeText(startedAt.toString()) }
            if (log != null) runCatching { File(d, PREVIOUS).writeText(log) }
        }
        synchronized(lines) { lines.clear() }
        session(false, "start")
        log("process started")
    }

    /**
     * The app's screens came to the front ([front]) or all left it: this is what tells a crash from a normal end. Written on the
     * recorder's own thread, at once and in order; on the main thread a file write once took over 2.5 seconds on the TV box (the
     * 23 November report), which froze the screen. [sync] writes on this thread (a crash, when the process is about to end).
     */
    fun session(front: Boolean, what: String, sync: Boolean = false) {
        val d = dir ?: return
        val line = (if (front) "front " else "back ") + stamp() + " " + what
        if (sync) runCatching { File(d, SESSION).writeText(line) } else writer.execute { runCatching { File(d, SESSION).writeText(line) } }
    }

    /** Write soon, on the recorder's own thread: what the main thread uses (it must never wait for the disk). */
    fun flush() { if (dir != null) writer.execute { write() } }

    /** Runs [block] on the recorder's own thread: small writes of the app's that must not hold the main thread. */
    fun background(block: () -> Unit) { writer.execute { runCatching(block) } }

    fun log(message: String) {
        val line = stamp() + " " + message
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        Log.i(TAG, line)
        if (dir != null && writeQueued.compareAndSet(false, true)) {
            writer.execute { writeQueued.set(false); write() }
        }
    }

    /** Write now, on this thread: for the moments when the process may not live long (the watchdog, a crash). Never the main thread. */
    fun flushNow() = write()

    /** The last [n] lines of this run. */
    fun tail(n: Int): String = synchronized(lines) { lines.toList().takeLast(n).joinToString("\n") }

    /**
     * The log as entries: a line that starts with a time, with the lines after it that do not (a stack's frames), so an entry is never
     * cut in two when the log is shown newest first.
     */
    fun entries(log: String): List<String> {
        val out = ArrayList<String>()
        for (line in log.lines()) {
            if (line.isEmpty()) continue
            if (STAMP.matches(line.take(12)) || out.isEmpty()) out += line else out[out.size - 1] = out.last() + "\n" + line
        }
        return out
    }

    /**
     * The freezes of the main thread with [frame] in their stack that the last two runs never came out of (the watchdog writes a line
     * when the thread is free again; a slow call that did come back is not one), each entry whole.
     */
    fun freezes(frame: String): List<String> = listOf(previous?.log.orEmpty(), older).flatMap { log ->
        val list = entries(log)
        list.filterIndexed { i, e -> e.contains(Watchdog.STUCK) && e.contains(frame) && list.drop(i + 1).none { it.contains(Watchdog.FREE) } }
    }

    fun froze(frame: String): Boolean = freezes(frame).isNotEmpty()


    private val STAMP = Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")

    /** The previous run's report has been seen: it is not shown again on the next start. */
    fun acknowledgePrevious() {
        val p = previous ?: return
        previous = Previous(false, p.lastSeen, p.log)
    }

    private val writeLock = Any()

    /** One writer at a time, and the file is replaced whole (written aside, then renamed), so it is never read half written. */
    private fun write() {
        val d = dir ?: return
        synchronized(writeLock) {
            val text = synchronized(lines) { lines.joinToString("\n") }
            runCatching {
                val tmp = File(d, "$FILE.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(File(d, FILE))) File(d, FILE).writeText(text)
            }
        }
    }

    private fun stamp(): String = synchronized(clock) { clock.format(Date()) }

    private fun read(f: File): String? = runCatching { f.takeIf { it.isFile }?.readText() }.getOrNull()

    /** Waits until every write that was asked for has happened (tests, which then put files of their own in place). */
    internal fun drainForTests() { runCatching { writer.submit {}.get() } }

    /** Back to nothing (tests). */
    internal fun resetForTests() {
        synchronized(lines) { lines.clear() }
        previous = null
        previousStart = null
        dir = null
    }
}
