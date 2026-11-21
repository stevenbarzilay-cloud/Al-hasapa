package app.couch.alhasapa.results

import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.catalog.Game

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

    fun detect(text: String, catalog: Catalog): Game? {
        val head = text.trim().lowercase().take(HEAD)
        if (head.isEmpty()) return null
        ALIASES.firstOrNull { head.contains(it.first) }?.let { return catalog.game(it.second) }
        val hits = catalog.games.filter { head.contains(it.n.lowercase()) }
        if (hits.isEmpty()) return null
        val longest = hits.maxOf { it.n.length }
        return hits.filter { it.n.length == longest }.singleOrNull()
    }

    private const val HEAD = 160
}
