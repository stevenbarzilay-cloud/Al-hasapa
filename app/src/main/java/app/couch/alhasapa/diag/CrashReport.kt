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

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(report(app, thread, error)) }
            runCatching { Flight.log("CRASH on ${thread.name}: $error"); Flight.session(false, "crash", sync = true); Flight.flushNow() }
            previous?.uncaughtException(thread, error)
        }
    }

    fun report(context: Context, thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().lines().take(MAX_STACK_LINES).joinToString("\n")
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

    /** The report of the last crash, if the app has not shown it yet. */
    fun pending(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.isFile }?.readText() }.getOrNull()

    fun clear(context: Context) { runCatching { File(context.filesDir, FILE).delete() } }
}
