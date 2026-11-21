package app.couch.alhasapa.ui

import android.content.ContentResolver
import android.provider.Settings

/** True when the person turned on "Remove animations" (or set the animation scale to 0): nothing in the app should move then. */
fun animationsRemoved(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
