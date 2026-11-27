package app.couch.alhasapa.player

import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.catalog.Game
import app.couch.alhasapa.catalog.Site
import org.json.JSONObject

/** Which browser profile (separate cookies and storage) a site's pages live in. */
object ProfileNames {
    const val GAMES = "games"
    const val STEVE = "steve"
    const val RONI = "roni"

    /** "games" for everything shared; LinkedIn is per person (a = the red piece / first name, b = the second). */
    fun forSite(site: Site?, who: String?): String =
        if (site?.profile == "player") (if (who == "b") RONI else STEVE) else GAMES

    /** A person's own corner: the first and second of us as always; anyone else (1.11, playing alone on a phone) a corner of their own. */
    fun forPerson(who: String): String = when {
        who == "b" -> RONI
        who == "a" || !app.couch.alhasapa.data.Couple.isId(who) -> STEVE
        else -> "p-$who"
    }
}

/** Everything the player needs to open one page. */
data class PlaySession(
    val title: String,
    val url: String,
    val profile: String,
    val allowedHosts: Set<String>,
    /** Free navigation: logging in, or a placeholder search link. */
    val roaming: Boolean,
    /** The game being played (null when only logging in). */
    val gameId: String?,
    /** For head-to-head games: "a" or "b", whose turn it is. */
    val who: String?,
    val loginMode: Boolean,
    /**
     * Played alone (1.11), by [who]: in that person's own corner (their profile, so a shared login's puzzle stays open for the couch),
     * and its result is sealed until the others play it alone too.
     */
    val solo: Boolean = false,
) {
    companion object {
        fun forGame(catalog: Catalog, state: JSONObject?, game: Game, who: String?, solo: Boolean = false): PlaySession {
            val url = catalog.urlFor(game, state)
            val alone = solo && who != null && !game.isDuel
            return PlaySession(
                title = if (alone) "⚔️ ${game.n}" else game.n, url = url,
                profile = if (alone) ProfileNames.forPerson(who!!) else ProfileNames.forSite(catalog.siteOf(game), who),
                allowedHosts = catalog.allowedHosts(game, url),
                roaming = catalog.roams(game, url),
                gameId = game.id, who = who, loginMode = false, solo = alone,
            )
        }

        fun forLogin(site: Site, title: String, profile: String): PlaySession = PlaySession(
            title = title, url = site.loginUrl ?: "https://${site.domains.first()}",
            profile = profile, allowedHosts = (site.domains + site.extraHosts).toSet(),
            roaming = true, gameId = null, who = null, loginMode = true,
        )
    }
}
