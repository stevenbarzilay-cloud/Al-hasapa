package app.couch.alhasapa.couches

import java.net.HttpURLConnection
import java.net.URL

/**
 * The one link by which the app is handed to a friend (2.0): the page of the latest release, the same for everyone. It leads to two
 * downloads, one for the phone and one for Android TV, and a few lines of instructions. It is for installing only: nothing of a
 * couch is in it (no id, no pairing code, no backup, nobody's name), and opening it on a device that has the app changes nothing
 * there.
 */
object AppLink {
    const val PAGE = "https://github.com/stevenbarzilay-cloud/Al-hasapa/releases/latest"
    const val PHONE = "https://github.com/stevenbarzilay-cloud/Al-hasapa/releases/latest/download/al-hasapa-phone.apk"
    const val TV = "https://github.com/stevenbarzilay-cloud/Al-hasapa/releases/latest/download/al-hasapa-tv.apk"

    /** Whether the link can be sent: both downloads are there; one of them is missing; or it could not be told (no network). */
    enum class Ready { YES, NO, UNKNOWN }

    /** Whether something can be downloaded from [url]: true, false (the server says there is nothing there), or null (not reached). */
    fun interface Probe { fun there(url: String): Boolean? }

    /**
     * The link is offered as ready only once both downloads answer: a friend who gets it must find the phone's and the TV's app
     * there, not a page with nothing to install.
     */
    fun check(probe: Probe = UrlProbe): Ready {
        val phone = probe.there(PHONE)
        val tv = probe.there(TV)
        return when {
            phone == true && tv == true -> Ready.YES
            phone == false || tv == false -> Ready.NO
            else -> Ready.UNKNOWN
        }
    }

    /** Asks the server for the file's headers only (it follows the release's redirect to where the file is kept). */
    object UrlProbe : Probe {
        override fun there(url: String): Boolean? = runCatching {
            var at = url
            repeat(5) {
                val c = URL(at).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 8_000; c.readTimeout = 8_000
                    c.requestMethod = "HEAD"
                    c.instanceFollowRedirects = false
                    val code = c.responseCode
                    when {
                        code in 200..299 -> return@runCatching true
                        code in 300..399 -> at = c.getHeaderField("Location")?.takeIf { it.startsWith("https://") } ?: return@runCatching false
                        code == 404 || code == 410 -> return@runCatching false
                        else -> return@runCatching null
                    }
                } finally { c.disconnect() }
            }
            null
        }.getOrNull()
    }
}
