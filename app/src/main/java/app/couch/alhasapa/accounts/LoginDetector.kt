package app.couch.alhasapa.accounts

import app.couch.alhasapa.catalog.Site

/**
 * BEST EFFORT. A website's session cookie names are not a public contract: this only looks for a cookie that normally means
 * "logged in" (NYT: NYT-S, LinkedIn: li_at, both configured in games.json). For a site whose cookie we do not know (Haaretz),
 * it looks for names that usually mean a session and otherwise says "unknown" rather than guessing. It never reads values.
 */
object LoginDetector {
    enum class Status { LOGGED_IN, LOGGED_OUT, UNKNOWN }

    private val SESSION_LOOKING = Regex("(?i)(sess|auth|login|logged|token|sid\\b|^sid|user|piano|__utp|tp_|tp\\.|subscriber|paywall|access)")

    /** The names (never the values) in a `Cookie` header such as "a=1; b=2". */
    fun names(cookieHeader: String?): List<String> =
        cookieHeader.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }

    fun status(cookieHeader: String?, site: Site): Status {
        val names = names(cookieHeader)
        if (names.isEmpty()) return Status.LOGGED_OUT
        if (site.sessionCookies.isNotEmpty()) {
            return if (names.any { it in site.sessionCookies }) Status.LOGGED_IN else Status.LOGGED_OUT
        }
        return if (names.any { SESSION_LOOKING.containsMatchIn(it) }) Status.LOGGED_IN else Status.UNKNOWN
    }
}
