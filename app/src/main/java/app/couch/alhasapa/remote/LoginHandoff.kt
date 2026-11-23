package app.couch.alhasapa.remote

import app.couch.alhasapa.catalog.Site
import org.json.JSONArray
import org.json.JSONObject

/**
 * Signing in on the phone and handing the login to the TV (1.9): typing a password with a remote is miserable, and NYT's sign-in page
 * stayed white on the TV box. What moves is what the phone's browser holds for the site (its cookies), encrypted on the paired link
 * ([LoginCrypto]); the TV puts them in its own browser for the same site. Values are never written to a log, only how many.
 *
 * What is not carried: anti-bot cookies (they belong to the device that earned them, and a copy looks like a bot to the site), and the
 * per-person LinkedIn logins (the TV keeps one browser profile; that can come later).
 */
object LoginHandoff {
    /** Where a cookie is read and written: the browser's cookie jar, or a stand-in in the tests. */
    interface Jar {
        /** The `Cookie` header the browser would send to [url] ("a=1; b=2"), or null. */
        fun get(url: String): String?
        fun set(url: String, setCookie: String)
        fun flush() {}
    }

    /** A site on the phone's list: whether the phone is signed in to it. */
    data class Item(val id: String, val name: String, val signedIn: Boolean)

    /** One cookie to set on the TV: the address it is set for and the whole `Set-Cookie` line. */
    data class Cookie(val url: String, val line: String)

    /** Cookies that bot protection hands to one device (DataDome, Cloudflare, Akamai, PerimeterX, Imperva). */
    private val DEVICE_BOUND = Regex("(?i)^(datadome|__cf_bm|cf_clearance|_abck|bm_s[a-z]*|ak_bmsc|_px[a-z0-9]*|incap_ses_.*|visid_incap_.*|reese84)$")

    /** About two months: the site renews its own cookies when it is used; an unused handoff should not live forever. */
    private const val MAX_AGE_S = 60L * 24 * 3600

    /** Sites whose login can be handed over: those on the accounts screen that share the TV's one browser profile. */
    fun canHand(site: Site): Boolean = site.account && site.profile == "games" && site.domains.isNotEmpty()

    /** The addresses read for [site]: each domain, with and without www, and the host of its sign-in page. */
    fun urls(site: Site): List<String> {
        val hosts = linkedSetOf<String>()
        for (d in site.domains) { hosts += d; hosts += "www.$d" }
        site.loginUrl?.let { u -> runCatching { java.net.URI(u).host }.getOrNull()?.let { hosts += it } }
        return hosts.map { "https://$it/" }
    }

    /**
     * What the phone's browser holds for [site], as cookies for the TV: one per name and domain, each for the whole site (its domain,
     * every path, https only). The browser does not say which of the site's hosts a cookie came from or when it expires, so each is set
     * for the domain it belongs to and for [MAX_AGE_S].
     */
    fun collect(site: Site, jar: Jar): List<Cookie> {
        val out = LinkedHashMap<String, Cookie>()
        for (url in urls(site)) {
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: continue
            val domain = site.domains.firstOrNull { host == it || host.endsWith(".$it") } ?: continue
            for (pair in jar.get(url).orEmpty().split(';')) {
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isEmpty() || !pair.contains('=') || DEVICE_BOUND.matches(name)) continue
                val key = "$domain|$name"
                if (key !in out) out[key] = Cookie("https://$domain/", "$name=$value; Domain=.$domain; Path=/; Secure; Max-Age=$MAX_AGE_S")
            }
        }
        return out.values.toList()
    }

    fun payload(siteId: String, cookies: List<Cookie>): String = JSONObject().put("site", siteId).put(
        "cookies", JSONArray().apply { cookies.forEach { put(JSONObject().put("u", it.url).put("c", it.line)) } },
    ).toString()

    /** (site, cookies), or null for anything else. Only https addresses on the site's own domains are taken. */
    fun parse(json: String, sites: Map<String, Site>): Pair<Site, List<Cookie>>? = runCatching {
        val o = JSONObject(json)
        val site = sites[o.getString("site")]?.takeIf { canHand(it) } ?: return null
        val a = o.getJSONArray("cookies")
        val list = (0 until minOf(a.length(), 200)).mapNotNull { i ->
            val c = a.getJSONObject(i)
            val url = c.optString("u"); val line = c.optString("c")
            val host = runCatching { java.net.URI(url) }.getOrNull()?.takeIf { it.scheme == "https" }?.host ?: return@mapNotNull null
            if (site.domains.none { host == it || host.endsWith(".$it") } || line.isEmpty() || line.length > 4_096) null else Cookie(url, line)
        }
        site to list
    }.getOrNull()

    /** Whether the phone's browser is signed in to [site] (by the site's session cookie, see LoginDetector). */
    fun signedIn(site: Site, jar: Jar): Boolean {
        val header = urls(site).mapNotNull { jar.get(it) }.joinToString("; ")
        return app.couch.alhasapa.accounts.LoginDetector.status(header, site) == app.couch.alhasapa.accounts.LoginDetector.Status.LOGGED_IN
    }

    /** Puts [cookies] in the TV's [jar] and writes it to disk. How many were set. */
    fun apply(jar: Jar, cookies: List<Cookie>): Int {
        for (c in cookies) jar.set(c.url, c.line)
        jar.flush()
        return cookies.size
    }
}
