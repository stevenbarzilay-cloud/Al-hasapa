package app.couch.alhasapa.remote

import android.content.Context
import android.widget.Toast
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.R
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.player.WebProfiles

/** The TV's side of the sign-in handoff, once the login is decrypted: into the browser of the site's profile, on the main thread. */
object LoginReceiver {
    /** The site's name and how many cookies were set, or null when the login is not one the TV takes. */
    fun receive(context: Context, payload: String): Pair<String, Int>? {
        val app = context.applicationContext as? CouchApp ?: return null
        val (site, cookies) = LoginHandoff.parse(payload, app.catalog.sites) ?: return null
        if (cookies.isEmpty()) return null
        val n = LoginHandoff.apply(jar(site.profile), cookies)
        Flight.log("logins: ${site.name} arrived from the phone ($n cookies)")
        Toast.makeText(app, app.getString(R.string.logins_arrived, site.name), Toast.LENGTH_LONG).show()
        return site.name to n
    }

    /** The browser's cookie jar of [profile] (the app's single one when profiles are off), as the handoff sees it. On the main thread. */
    fun jar(profile: String): LoginHandoff.Jar {
        val cm = WebProfiles.cookies(profile)
        return object : LoginHandoff.Jar {
            override fun get(url: String): String? = cm.getCookie(url)
            override fun set(url: String, setCookie: String) { cm.setCookie(url, setCookie) }
            override fun flush() { cm.flush() }
        }
    }
}
