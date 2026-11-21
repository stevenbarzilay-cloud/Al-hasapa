package app.couch.alhasapa.tv

import android.content.Context
import android.content.res.Configuration
import app.couch.alhasapa.BuildConfig

object TvMode {
    /** True in the TV build, and for any build running on a television (so the phone APK also works if put on a TV). */
    fun isTelevision(context: Context): Boolean =
        BuildConfig.TV || (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
}
