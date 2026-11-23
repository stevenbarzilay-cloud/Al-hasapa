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
        app.couch.alhasapa.player.WebProfiles.offBecause?.let { appendLine("separate logins: off on this device (the WebView got stuck in: $it)") }
        Startup.last(context)?.let { appendLine(); appendLine("--- the last start ---"); appendLine(it) }
        CrashReport.pending(context)?.let { appendLine(); appendLine("--- the last crash ---"); appendLine(it.take(1_800)) }
        val p = Flight.previous
        if (p != null && p.log.isNotBlank()) {
            appendLine()
            appendLine("--- the previous run, newest first (${if (p.endedInFront) "ended while in use" else "ended normally"}; last seen ${p.lastSeen}) ---")
            exitReasons(context)?.let { appendLine(it) }
            appendLine(newestFirst(p.log, 40))
        }
        appendLine()
        appendLine("--- this run (newest first) ---")
        append(newestFirst(Flight.tail(60), 40))
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
            exitReasons(context)?.let { appendLine(it) }
            appendLine("--- what happened just before (newest first) ---")
            append(newestFirst(p.log, 30))
        }
    }

    /** The log's entries, newest first, each whole (a frozen thread's stack stays under its line, in its own order). */
    private fun newestFirst(log: String, n: Int): String = Flight.entries(log).takeLast(n).asReversed().joinToString("\n")

    /**
     * Android 11 and later say why the last processes ended. The app's own process is the one that matters (the WebView's page processes
     * end too, "isolated not needed", and are only mentioned); when it froze, Android kept the main thread's stack, which is shown.
     */
    private fun exitReasons(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val all = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            val app = all.firstOrNull { it.processName == context.packageName }
            buildString {
                if (app != null) {
                    append("android says the app ended: ${why(app.reason)} at ${Date(app.timestamp)}")
                    app.description?.let { append(", $it") }
                    if (app.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                        runCatching {
                            app.traceInputStream?.bufferedReader()?.use { r ->
                                val buf = CharArray(400_000); val n = r.read(buf).coerceAtLeast(0); val trace = String(buf, 0, n)
                                val at = trace.indexOf("\"main\"")
                                if (at >= 0) { appendLine(); append("main thread when it froze:\n" + trace.substring(at).lineSequence().take(28).joinToString("\n")) }
                            }
                        }
                    }
                }
                all.filter { it.processName != context.packageName }.take(2).forEach {
                    if (isNotEmpty()) appendLine()
                    append("  (${it.processName.substringAfterLast(':').take(40)}: ${why(it.reason)} at ${Date(it.timestamp)})")
                }
            }.ifBlank { null }
        }.getOrNull()
    }

    private fun why(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_ANR -> "froze (ANR)"
        android.app.ApplicationExitInfo.REASON_CRASH -> "crashed (Java)"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed (native)"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "out of memory"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the person"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the person"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "closed itself"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too much"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "something it depends on died"
        android.app.ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "reason $reason"
    }

    private const val MB = 1024L * 1024L
}
