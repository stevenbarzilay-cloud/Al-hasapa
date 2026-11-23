package app.couch.alhasapa.diag

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * When the app crashes, the reason is written to a file before it dies, and the next start shows it on screen with a button to
 * continue. Without a computer nobody can read a log, and "it crashed" is not something that can be fixed. The report holds the
 * app and device versions, the start-up timings and the stack trace: nothing about the person.
 */
object CrashReport {
    private const val FILE = "last-crash.txt"
    private const val MAX_STACK_LINES = 60

    /** The longest circle of calls that is folded (see [fold]). */
    private const val MAX_CYCLE = 6

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(report(app, thread, error)) }
            runCatching { Flight.log("CRASH on ${thread.name}: $error"); Flight.session(false, "crash", sync = true); Flight.flushNow() }
            previous?.uncaughtException(thread, error)
        }
    }

    fun report(context: Context, thread: Thread, error: Throwable): String {
        val trace = fold(StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().lines()).take(MAX_STACK_LINES).joinToString("\n")
        return buildString {
            appendLine("crashed ${Date()}")
            appendLine(Startup.environment(context))
            appendLine("thread: ${thread.name}")
            appendLine("--- start-up marks ---")
            appendLine(Startup.summary())
            appendLine("--- stack ---")
            appendLine(trace)
            appendLine("--- just before ---")
            append(Flight.tail(25))
        }
    }

    /**
     * A stack that goes round in circles, said once: the lines that repeat, then how often. A function that calls itself until the
     * stack runs out fills a thousand lines with the same two; in November 2026 that was nine screenshots of one crash, none of which
     * showed the app's version or what the error was.
     */
    fun fold(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            var folded = false
            for (p in 1..MAX_CYCLE) {
                if (i + 2 * p > lines.size) break
                var times = 1
                while (i + (times + 1) * p <= lines.size && (0 until p).all { lines[i + it] == lines[i + times * p + it] }) times++
                if (times >= 3) {
                    for (k in 0 until p) out += lines[i + k]
                    out += "\t… the ${if (p == 1) "line" else "$p lines"} above, $times times in a row"
                    i += times * p
                    folded = true
                    break
                }
            }
            if (!folded) out += lines[i++]
        }
        return out
    }

    /**
     * The report's two facts, for the top of the screen where no scrolling hides them: which version of the app it was, and what
     * happened. "app 1.9.0 (release) · java.lang.StackOverflowError: stack size 8188KB".
     */
    fun headline(report: String): String {
        val lines = report.lines()
        val app = lines.firstOrNull { it.startsWith("app ") }?.substringBefore(',')?.trim()
        val stack = lines.indexOf("--- stack ---")
        val what = (if (stack >= 0) lines.getOrNull(stack + 1) else lines.firstOrNull())?.trim()?.take(200)
        return listOfNotNull(app, what?.takeIf { it.isNotEmpty() }).joinToString(" · ")
    }

    /** The report of the last crash, if the app has not shown it yet. */
    fun pending(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.isFile }?.readText() }.getOrNull()

    fun clear(context: Context) { runCatching { File(context.filesDir, FILE).delete() } }
}
