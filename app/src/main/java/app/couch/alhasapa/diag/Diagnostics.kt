package app.couch.alhasapa.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import java.util.Date

/**
 * Everything worth knowing when something goes wrong, as plain text that can be photographed: the versions, the memory, the last start,
 * the last crash, how the previous run ended, and what the app did lately. Shown in the app's own information panel (no WebView needed)
 * and in the hub's settings page.
 */
object Diagnostics {
    /** One line about memory: the app's own use and what the device has left. */
    fun memory(context: Context): String {
        val rt = Runtime.getRuntime()
        val heap = (rt.totalMemory() - rt.freeMemory()) / MB
        val heapMax = rt.maxMemory() / MB
        val native = Debug.getNativeHeapAllocatedSize() / MB
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        return "memory: app heap $heap/$heapMax MB, native $native MB; device free ${mi.availMem / MB} of ${mi.totalMem / MB} MB" +
            (if (mi.lowMemory) " (LOW)" else "") + ", class ${am?.memoryClass ?: 0} MB"
    }

    fun text(context: Context): String = buildString {
        appendLine(Startup.environment(context))
        appendLine(memory(context))
        val keys = app.couch.alhasapa.remote.RemoteKeys.ui.value
        if (keys.listening) appendLine("phone keyboard: address ${keys.address.ifBlank { "unknown" }}, phones connected: ${keys.phones.joinToString().ifBlank { "none" }}")
        Startup.last(context)?.let { appendLine(); appendLine("--- the last start ---"); appendLine(it) }
        CrashReport.pending(context)?.let { appendLine(); appendLine("--- the last crash ---"); appendLine(it.take(1_800)) }
        val p = Flight.previous
        if (p != null && p.log.isNotBlank()) {
            appendLine()
            appendLine("--- the previous run, newest first (${if (p.endedInFront) "ended while in use" else "ended normally"}; last seen ${p.lastSeen}) ---")
            exitReason(context)?.let { appendLine(it) }
            appendLine(p.log.lines().takeLast(40).asReversed().joinToString("\n"))
        }
        appendLine()
        appendLine("--- this run (newest first) ---")
        append(Flight.tail(40).lines().asReversed().joinToString("\n"))
    }.trim()

    /**
     * When the previous run ended while the app was in use and it was not a Java crash (that has its own report): what happened just before.
     * Null when there is nothing to tell.
     */
    fun unexpectedEnd(context: Context): String? {
        val p = Flight.previous ?: return null
        if (!p.endedInFront || CrashReport.pending(context) != null) return null
        return buildString {
            appendLine("stopped while in use; last seen ${p.lastSeen}")
            appendLine(Startup.environment(context))
            exitReason(context)?.let { appendLine(it) }
            appendLine("--- what happened just before (newest first) ---")
            append(p.log.lines().takeLast(45).asReversed().joinToString("\n"))
        }
    }

    /** Android 11 and later say why the last process ended (a crash, a freeze, low memory). The Mi Box is older and does not. */
    private fun exitReason(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val e = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
            val why = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_ANR -> "froze (ANR)"
                android.app.ApplicationExitInfo.REASON_CRASH -> "crashed (Java)"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed (native)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "out of memory"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the person"
                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "closed itself"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
                else -> "reason ${e.reason}"
            }
            "android says: $why at ${Date(e.timestamp)}" + (e.description?.let { ", $it" } ?: "")
        }.getOrNull()
    }

    private const val MB = 1024L * 1024L
}
