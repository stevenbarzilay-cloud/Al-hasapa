package app.couch.alhasapa.player

import app.couch.alhasapa.catalog.Catalog
import java.net.URI

/**
 * What the isolated player may navigate to. The rule: stay on the game's own sites. Anything else (ads, articles, "get the
 * app" pages) is refused with a small toast instead of dragging the TV into another site, and links that would launch another
 * app (intent:, market:, nytimes://, linkedin://...) are never followed: they would open apps that are not ours on the TV.
 */
object NavigationPolicy {
    sealed interface Decision {
        data object Allow : Decision
        data class OffDomain(val host: String?) : Decision
        data class BlockedScheme(val scheme: String?) : Decision
    }

    /**
     * @param allowedHosts the domains this game may stay on (itself and subdomains)
     * @param roaming true while logging in (single sign-on bounces between identity providers) or for a placeholder link that
     *   is only a search: any https page is fine, but still never another app.
     */
    fun decide(url: String, allowedHosts: Set<String>, roaming: Boolean): Decision {
        if (url == "about:blank") return Decision.Allow
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return Decision.BlockedScheme(null)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return Decision.BlockedScheme(scheme)
        if (roaming) return Decision.Allow
        val host = uri.host?.lowercase()
        return if (Catalog.hostAllowed(host, allowedHosts)) Decision.Allow else Decision.OffDomain(host)
    }
}
