package app.couch.alhasapa.catalog

import android.content.Context
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

data class Site(
    val id: String,
    val name: String,
    val color: String,
    /** Has a login the person keeps (NYT, Haaretz, LinkedIn): listed on the accounts screen. */
    val account: Boolean,
    val label: String,
    /** The player stays on these domains (and their subdomains) for this site's games. */
    val domains: List<String>,
    /** Extra hosts allowed during login/paywall flows (payment and identity providers). */
    val extraHosts: List<String>,
    val loginUrl: String?,
    /** Cookies whose presence means "logged in" (best effort; empty = unknown, a pattern is used instead). */
    val sessionCookies: List<String>,
    /** "games" = one shared browser profile; "player" = a separate profile per person (LinkedIn). */
    val profile: String,
)

data class Game(
    val id: String,
    val n: String,
    val s: String,
    val k: String,
    val m: String,
    val u: String,
    val d: String,
    val lib: Boolean,
    val why: String?,
    /** guesses | mistakes | hints | time | words | none */
    val metric: String,
    /** The link is a Google search standing in for a direct one. */
    val placeholder: Boolean,
    /**
     * Its place among the suggestions (1 is offered first), for the ones the two of us picked by hand; null for the rest, which come
     * after them in the catalogue's own order.
     */
    val pick: Int? = null,
    /**
     * Which symbol its cushion wears on the TV's home, where the kind alone does not say ("cinema" for the film games, whose kind is
     * "ידע" like any quiz); null for the rest, which go by their kind. See home/Category.
     */
    val cat: String? = null,
) {
    /** Head to head: each person plays alone and pastes their own result (the LinkedIn games). */
    val isDuel: Boolean get() = s == "li"
}

/** The catalogue (assets/games.json): the sites and the games, with the person's own links applied on top. */
class Catalog(val sites: Map<String, Site>, val games: List<Game>) {
    private val byId = games.associateBy { it.id }

    fun game(id: String): Game? = byId[id]

    fun siteOf(game: Game): Site? = sites[game.s]

    /** The game's link: the person's own if they set one, else the catalogue's. */
    fun urlFor(game: Game, state: JSONObject?): String =
        state?.optJSONObject("urls")?.optString(game.id)?.takeIf { it.isNotBlank() } ?: game.u

    /** Is the game in the shelf from the start (or put there / taken off by the person)? */
    fun inLib(game: Game, state: JSONObject?): Boolean {
        val pref = state?.optJSONObject("prefs")?.optString(game.id).orEmpty()
        return if (pref.isNotEmpty()) pref == "yes" else game.lib
    }

    /**
     * Hosts the player may stay on for this game: the site's domains and login helpers, plus the host of the game's own
     * link (so a new direct link on some other site just works). A Google-search placeholder may roam until it is replaced.
     */
    fun allowedHosts(game: Game, effectiveUrl: String): Set<String> {
        val site = siteOf(game)
        val hosts = linkedSetOf<String>()
        site?.domains?.let { hosts += it }
        site?.extraHosts?.let { hosts += it }
        hostOf(effectiveUrl)?.let { hosts += registrable(it) }
        return hosts
    }

    /** True for a link that is only a stand-in: the player lets the person follow it to a real page. */
    fun roams(game: Game, effectiveUrl: String): Boolean = game.placeholder && effectiveUrl == game.u

    /** What the hub needs: sites and games, each game with its effective link `u` and the catalogue's own `u0`. */
    fun toHubJson(state: JSONObject?): String {
        val g = JSONArray()
        for (game in games) {
            g.put(
                JSONObject().put("id", game.id).put("n", game.n).put("s", game.s).put("k", game.k).put("m", game.m)
                    .put("u", urlFor(game, state)).put("u0", game.u).put("d", game.d).put("metric", game.metric)
                    .apply {
                        if (game.lib) put("lib", 1); if (game.why != null) put("why", game.why); if (game.placeholder) put("placeholder", true)
                        if (game.pick != null) put("pick", game.pick)
                    },
            )
        }
        val s = JSONObject()
        for (site in sites.values) {
            s.put(site.id, JSONObject().put("name", site.name).put("color", site.color).put("account", site.account).put("label", site.label))
        }
        return JSONObject().put("sites", s).put("games", g).toString()
    }

    /**
     * The same catalogue plus the games the person added (state.custom): each needs an id, a name and an https link; an unknown site
     * becomes "my" (our own games); an id that already exists is ignored. Anything malformed is skipped, never fatal.
     */
    fun withCustom(custom: JSONArray?): Catalog {
        if (custom == null || custom.length() == 0) return this
        val seen = games.map { it.id }.toMutableSet()
        val extra = ArrayList<Game>()
        for (i in 0 until custom.length()) {
            val o = custom.optJSONObject(i) ?: continue
            val g = runCatching { parseGame(o, defaultSite = "my") }.getOrNull() ?: continue
            if (!g.u.startsWith("https://") || g.n.isBlank() || !seen.add(g.id)) continue
            extra += if (g.s in sites) g else g.copy(s = "my")
        }
        return if (extra.isEmpty()) this else Catalog(sites, games + extra)
    }

    companion object {
        fun parseGame(o: JSONObject, defaultSite: String? = null): Game = Game(
            id = o.getString("id"), n = o.getString("n"), s = if (defaultSite != null) o.optString("s", defaultSite) else o.getString("s"),
            k = o.optString("k"), m = o.optString("m"), u = o.getString("u"), d = o.optString("d"),
            lib = o.optInt("lib", 0) == 1, why = o.optString("why").takeIf { it.isNotBlank() },
            metric = o.optString("metric", "none"), placeholder = o.optBoolean("placeholder", false),
            pick = o.optInt("pick", 0).takeIf { it > 0 }, cat = o.optString("cat").takeIf { it.isNotBlank() },
        )

        /** The suggestions in the order they are offered: the hand-picked ones by their [Game.pick], then the rest as the catalogue has them. */
        fun offered(games: List<Game>): List<Game> = games.sortedBy { it.pick ?: Int.MAX_VALUE }

        fun parse(json: String): Catalog {
            val root = JSONObject(json)
            val sites = LinkedHashMap<String, Site>()
            val so = root.getJSONObject("sites")
            for (id in so.keys()) {
                val o = so.getJSONObject(id)
                sites[id] = Site(
                    id = id,
                    name = o.getString("name"),
                    color = o.optString("color", "#cccccc"),
                    account = o.optBoolean("account", false),
                    label = o.optString("label", o.getString("name")),
                    domains = strings(o.optJSONArray("domains")),
                    extraHosts = strings(o.optJSONArray("extraHosts")),
                    loginUrl = o.optString("loginUrl").takeIf { it.isNotBlank() },
                    sessionCookies = strings(o.optJSONArray("sessionCookies")),
                    profile = o.optString("profile", "games"),
                )
            }
            val games = ArrayList<Game>()
            val ga = root.getJSONArray("games")
            for (i in 0 until ga.length()) {
                games += parseGame(ga.getJSONObject(i))
            }
            return Catalog(sites, games)
        }

        fun load(context: Context): Catalog =
            parse(context.assets.open("games.json").bufferedReader(Charsets.UTF_8).use { it.readText() })

        private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

        fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

        /** "www.nytimes.com" -> "nytimes.com"; deliberately simple (no public-suffix list): good enough for an allowlist of known sites. */
        fun registrable(host: String): String = host.removePrefix("www.")

        /** Does [host] belong to one of the [allowed] domains (itself or a subdomain)? */
        fun hostAllowed(host: String?, allowed: Set<String>): Boolean {
            if (host == null) return false
            val h = host.lowercase()
            return allowed.any { h == it || h.endsWith(".$it") }
        }
    }
}
