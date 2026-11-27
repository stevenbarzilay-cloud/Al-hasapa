package app.couch.alhasapa.results

import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.catalog.Game
import org.json.JSONObject

/**
 * Which game a shared text is from, judged by its header (the first line or two: "Wordle 1,618 3/6", "Connections / Puzzle #512",
 * "Queens #612 | 1:24"). Returns null when it cannot tell, and the share screen then asks; guessing wrong would score the
 * wrong game, so a tie is also a null.
 */
object GameDetector {
    /** Spellings in shares that are not the catalogue's name. */
    private val ALIASES = listOf(
        "mini crossword" to "mini",
        "times crossword" to "crossword",
        "daily crossword" to "crossword",
        "sports edition" to "connsports",
    )

    /** [state]: for our own links of the games (see [Catalog.urlFor]). */
    fun detect(text: String, catalog: Catalog, state: JSONObject? = null): Game? {
        val head = text.trim().lowercase().take(HEAD)
        if (head.isEmpty()) return null
        ALIASES.firstOrNull { head.contains(it.first) }?.let { return catalog.game(it.second) }
        val hits = catalog.games.filter { head.contains(it.n.lowercase()) }
        if (hits.isNotEmpty()) {
            val longest = hits.maxOf { it.n.length }
            hits.filter { it.n.length == longest }.singleOrNull()?.let { return it }
        }
        return byLink(text, catalog, state)
    }

    private val LINK = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    /** An address as two shares of one page say it alike: no scheme, no "www.", no query or anchor, no closing slash. */
    private fun norm(u: String): String =
        u.trim().lowercase().substringBefore('#').substringBefore('?').removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    /**
     * A share that names no game but carries its link (1.11.2: Haaretz's "כל הכבוד! הצלחת לזהות את הדמות תוך 6 צעדים" and the
     * game's address): the game whose link it is, ours or the catalogue's; or the page by its id (the last part of its address, long
     * enough to be one), whatever the rest of the address says.
     */
    private fun byLink(text: String, catalog: Catalog, state: JSONObject?): Game? {
        val links = LINK.findAll(text).map { norm(it.value) }.toList()
        if (links.isEmpty()) return null
        fun linksOf(g: Game) = setOf(norm(catalog.urlFor(g, state)), norm(g.u))
        catalog.games.firstOrNull { g -> linksOf(g).any { it in links } }?.let { return it }
        val ids = links.map { it.substringAfterLast('/') }.filter { it.length >= MIN_ID }.toSet()
        if (ids.isEmpty()) return null
        return catalog.games.filter { g -> linksOf(g).any { it.substringAfterLast('/').let { id -> id.length >= MIN_ID && id in ids } } }.singleOrNull()
    }

    private const val MIN_ID = 16

    private const val HEAD = 160
}
