package app.couch.alhasapa.player

import android.content.Context
import androidx.core.content.edit
import app.couch.alhasapa.diag.Flight

/**
 * Sites whose game is drawn without the graphics chip on this device ("ציור קל"): the WebView gets a software layer, so the page is
 * painted by the processor and the box's graphics only show the finished picture. Slower, and a page that needs WebGL or video
 * loses them, but it cannot hold the box's drawing stuck.
 *
 * Turned on by the draw guard after a game held the TV's drawing stuck (November 2026: Krillion, 20 seconds, on a box that then did
 * not wake from standby), and by hand in the game's menu, where it can be turned off again. Kept on the device, not in the state
 * (it is about this box, not the people, and a backup should not carry it to the phone).
 */
object LightDrawing {
    private const val PREFS = "drawing"

    /** The host of the game in front, for the draw guard (which runs on its own thread while the main thread is stuck). */
    @Volatile var front: String? = null

    fun key(host: String?): String? = host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }

    fun isOn(context: Context, host: String?): Boolean {
        val k = key(host) ?: return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(k, false)
    }

    /**
     * Once per start on a TV: a game that held the picture stuck in the last two runs (seen in the flight recorder, before the guard
     * existed or before it acted) is drawn the light way from now on. Each freeze is acted on once, so turning a site back to full
     * drawing in the menu is not undone by the same old freeze.
     */
    fun fromEarlierFreezes(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val found = Flight.drawFreezes()
        val seen = prefs.getStringSet(SEEN, null).orEmpty()
        val fresh = found.filter { it.first !in seen }
        if (fresh.isEmpty()) return
        for (host in fresh.map { it.second }.distinct()) {
            set(context, host, true)
            Flight.log("drawing: $host held the picture stuck in an earlier run; drawn the light way from now on")
        }
        prefs.edit(commit = true) { putStringSet(SEEN, found.map { it.first }.toSet()) }
    }

    private const val SEEN = "_freezes_seen"

    /** Written at once (commit): the guard may end the app right after. Safe from any thread. */
    fun set(context: Context, host: String?, on: Boolean) {
        val k = key(host) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { if (on) putBoolean(k, true) else remove(k) }
    }
}

/** The host of a page's address, or null (lenient: any odd address gives null rather than an error). */
internal fun hostOf(url: String?): String? = url?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
