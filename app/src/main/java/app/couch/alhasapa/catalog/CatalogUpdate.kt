package app.couch.alhasapa.catalog

import android.content.Context
import androidx.core.content.edit
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.diag.Flight

/**
 * What a new catalogue asks of the choices already made on this device, once.
 *
 * The shelf and the suggestions follow the people's own choices first ("yes", "no", "later" for a game) and the catalogue only where
 * they made none. In November 2026 (1.9.4) three games that were already in the catalogue were asked for by name: Framed onto the
 * shelf, TimeGuessr and Cinematrix among the suggestions offered first. A device on which one of them had been skipped or put off
 * would not show it where it was asked for. So, once, such a "no" or "later" is forgotten for these three; a "yes" stays (the game is
 * on the shelf already), and so does every later choice: after this ran, skipping one of them again is final like any other skip.
 */
object CatalogUpdate {
    /** The catalogue's revision that this build brings. */
    const val LATEST = 2

    private val ASKED_FOR_BY_NAME = listOf("framed", "timeguessr", "cinematrix")
    private const val PREFS = "catalogue"
    private const val APPLIED = "applied"

    fun apply(context: Context, repo: StateRepository) {
        val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (seen.getInt(APPLIED, 1) >= LATEST) return
        val forgotten = forget(repo)
        if (forgotten.isNotEmpty()) Flight.log("catalogue: asked for by name, so an earlier skip is forgotten: ${forgotten.joinToString()}")
        seen.edit { putInt(APPLIED, LATEST) }
    }

    /** The games whose "no" or "later" was forgotten (nothing is written when there are none: a device with no state stays without one). */
    fun forget(repo: StateRepository): List<String> {
        val chosen = repo.snapshot().optJSONObject("prefs") ?: return emptyList()
        val stale = ASKED_FOR_BY_NAME.filter { chosen.optString(it) == "no" || chosen.optString(it) == "later" }
        if (stale.isNotEmpty()) repo.update { s -> s.optJSONObject("prefs")?.let { p -> stale.forEach { p.remove(it) } } }
        return stale
    }
}
