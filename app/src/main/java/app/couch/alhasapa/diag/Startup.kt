package app.couch.alhasapa.diag

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.webkit.WebView
import app.couch.alhasapa.BuildConfig
import java.io.File

/**
 * How long a start takes, mark by mark, from the moment the process was created. On a slow TV box the first seconds are the whole
 * experience, and nobody can see them without numbers: the last start's marks are kept and shown in the settings page.
 */
object Startup {
    private val marks = ArrayList<Pair<String, Long>>()

    @Synchronized
    fun mark(name: String) {
        marks += name to (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
    }

    @Synchronized
    fun summary(): String = marks.joinToString("\n") { "${it.first}: ${it.second} ms" }

    /** Written once the hub is up, so the next look at the settings page has the numbers of the last start. */
    fun save(context: Context) {
        runCatching { File(context.filesDir, FILE).writeText(summary()) }
    }

    fun last(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.isFile }?.readText() }.getOrNull()

    /** What this device and app are: for the settings page and for crash reports. */
    fun environment(context: Context): String {
        val webview = runCatching { WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } }.getOrNull() ?: "no WebView"
        val build = if (BuildConfig.DEBUG) "debug" else "release"
        return "app ${BuildConfig.VERSION_NAME} ($build), android ${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}, WebView $webview"
    }

    private const val FILE = "startup.txt"
}
