package app.couch.alhasapa.tv

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.edit
import app.couch.alhasapa.BuildConfig

object TvMode {
    /** True in the TV build, and for any build running on a television (so the phone APK also works if put on a TV). */
    fun isTelevision(context: Context): Boolean = BuildConfig.TV || isTvDevice(context)

    /** Whether the device itself is a television (by its UI mode, or Android TV's own feature), whichever of the two apps runs on it. */
    fun isTvDevice(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
            runCatching { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }.getOrDefault(false)

    /** In the tests: the TV app is taken to run on a phone (true) or on a TV (false), whatever the test's device is; null: as it is. */
    @Volatile var onPhoneForTest: Boolean? = null

    /**
     * The TV app installed on a phone (2.0: a friend picked the wrong one of the two downloads, and so did we, once). It says so first,
     * instead of showing a TV's screens on a phone, until the person chooses to go on with it all the same; meanwhile it does not
     * announce itself on the network as a TV.
     */
    fun wrongApp(context: Context): Boolean {
        val onPhone = onPhoneForTest ?: (BuildConfig.TV && Build.FINGERPRINT != "robolectric" && !isTvDevice(context))
        return onPhone && !prefs(context).getBoolean(GO_ON, false)
    }

    /** The person chose to go on with the TV app on this device all the same (said once). */
    fun goOn(context: Context) { prefs(context).edit { putBoolean(GO_ON, true) } }

    /** In the tests: as if it was never said. */
    fun forget(context: Context) { prefs(context).edit { remove(GO_ON) } }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("device", Context.MODE_PRIVATE)
    private const val GO_ON = "wrong_app_go_on"
}
