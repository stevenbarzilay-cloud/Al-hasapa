package app.couch.alhasapa.player

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * The "לפתוח בכרום" fallback. A Custom Tab runs in the phone's Chrome PROFILE (its cookies and autofill), so it is the one
 * place where the isolation is deliberately left: it only opens when the person taps it (for example when a site only offers
 * "Continue with Google", which cannot work inside an app), never on its own.
 */
object ChromeTab {
    fun open(context: Context, url: String) {
        runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url)) }
    }
}
