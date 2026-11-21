package app.couch.alhasapa.dnd

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaRouter
import android.provider.Settings
import android.view.Display
import androidx.core.content.edit
import app.couch.alhasapa.data.CouchState
import java.time.ZonedDateTime

/** What the "do not disturb" chip decides from. A seam so the rule can be tested without a phone. */
interface DndSignals {
    /** The screen is being cast or mirrored right now (best effort). */
    fun isCasting(): Boolean

    /** Do Not Disturb is already on. */
    fun isDndOn(): Boolean

    /** The evening the chip was last shown for (see [DndPrompt.eveningKey]), or null. */
    fun lastShownEvening(): String?

    fun markShown(evening: String)

    companion object {
        fun real(context: Context): DndSignals = RealDndSignals(context.applicationContext)
    }
}

object DndPrompt {
    /** "An evening" runs until 4 in the morning, so a game after midnight is still tonight. */
    fun eveningKey(now: ZonedDateTime = ZonedDateTime.now()): String = CouchState.dayKey(now.minusHours(4).toLocalDate())

    /** Once per evening, only while casting, and not when Do Not Disturb is already on. */
    fun shouldShow(signals: DndSignals, now: ZonedDateTime = ZonedDateTime.now()): Boolean =
        signals.lastShownEvening() != eveningKey(now) && signals.isCasting() && !signals.isDndOn()

    /** The Do Not Disturb settings, or the nearest screen that exists on this phone. */
    fun settingsIntents(): List<Intent> = listOf(
        Intent("android.settings.ZEN_MODE_SETTINGS"),
        Intent(Settings.ACTION_SOUND_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    fun openSettings(context: Context) {
        for (i in settingsIntents()) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}

private class RealDndSignals(private val context: Context) : DndSignals {
    private val prefs get() = context.getSharedPreferences("dnd", Context.MODE_PRIVATE)

    /**
     * Android has no single "am I casting?" call. Two signals, either one counts: a second display (a presentation display
     * or a cast mirror that is listed) and a selected media route that is not the phone's own.
     */
    @Suppress("DEPRECATION") // MediaRouter's classic route API is the one that reports a mirroring route to the platform.
    override fun isCasting(): Boolean = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val extraDisplay = dm.displays.any { it.displayId != Display.DEFAULT_DISPLAY }
        val mr = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
        val route = mr.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
        extraDisplay || (route != null && route != mr.defaultRoute)
    }.getOrDefault(false)

    override fun isDndOn(): Boolean = runCatching {
        val f = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter
        f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }.getOrDefault(false)

    override fun lastShownEvening(): String? = prefs.getString("evening", null)

    override fun markShown(evening: String) = prefs.edit { putString("evening", evening) }
}
