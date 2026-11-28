package app.couch.alhasapa.catalog

import android.content.Context
import androidx.core.content.edit
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.diag.Flight

/**
 * What a new catalogue asks of the choices already made on this device, once.
 *
 * The shelf and the suggestions follow the people's own choices first ("yes", "no", "later" for a game) and the catalogue only where
 * they made none. When games already in the catalogue are asked for by name, a device on which one of them had been skipped or put off
 * would not show it where it was asked for. So, once for each revision, such a "no" or "later" is forgotten for the games that revision
 * names; a "yes" stays (the game is on the shelf already), and so does every later choice: after it ran, skipping one of them again is
 * final like any other skip.
 *
 * Revision 2 (1.9.4, November 2026): Framed onto the shelf, TimeGuessr and Cinematrix among the suggestions offered first.
 * Revision 3 (2.0.3, November 2026): every LinkedIn game on the shelf ("all of them, please"): Crossclimb, Mini Sudoku, Patches and
 * Wend beside the four that were there, and any of the eight that had been taken off it.
 */
object CatalogUpdate {
    /** The catalogue's revision that this build brings. */
    const val LATEST = 3

    /** The games each revision asked for by name. */
    private val ASKED_FOR_BY_NAME = mapOf(
        2 to listOf("framed", "timeguessr", "cinematrix"),
        3 to listOf("queens", "tango", "zip", "pinpoint", "crossclimb", "minisudoku", "patches", "wend"),
    )
    private const val PREFS = "catalogue"
    private const val APPLIED = "applied"

    fun apply(context: Context, repo: StateRepository) {
        val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val applied = seen.getInt(APPLIED, 1)
        if (applied >= LATEST) return
        val asked = ASKED_FOR_BY_NAME.filterKeys { it > applied }.toSortedMap().values.flatten()
        val forgotten = forget(repo, asked)
        if (forgotten.isNotEmpty()) Flight.log("catalogue: asked for by name, so an earlier skip is forgotten: ${forgotten.joinToString()}")
        seen.edit { putInt(APPLIED, LATEST) }
    }

    /** Of [ids], the games whose "no" or "later" was forgotten (nothing is written when there are none: a device with no state stays without one). */
    fun forget(repo: StateRepository, ids: List<String>): List<String> {
        val chosen = repo.snapshot().optJSONObject("prefs") ?: return emptyList()
        val stale = ids.filter { chosen.optString(it) == "no" || chosen.optString(it) == "later" }
        if (stale.isNotEmpty()) repo.update { s -> s.optJSONObject("prefs")?.let { p -> stale.forEach { p.remove(it) } } }
        return stale
    }
}
