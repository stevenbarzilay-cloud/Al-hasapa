package app.couch.alhasapa.remote

import android.content.Context
import androidx.core.content.edit
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.data.SoloSide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * A game the phone's holder played alone for a friend's couch's contest (2.0): the couch and the player they are there, the game and
 * the puzzle's day, and their side. [sent]: the couch's TV said it keeps it. Until then it waits here, and goes when that TV is
 * reached again; it is never counted anywhere else meanwhile, and never twice (the TV keeps the newest of a side, once).
 */
data class GuestEntry(
    val couch: String, val couchName: String, val who: String, val whoName: String,
    val day: String, val game: String, val side: SoloSide, val sent: Boolean,
) {
    val key: String get() = "$couch|$day|$game"

    fun toJson(): JSONObject = JSONObject().put("couch", couch).put("couchName", couchName).put("who", who).put("whoName", whoName)
        .put("day", day).put("game", game).put("side", side.toJson()).put("sent", sent)

    companion object {
        fun fromJson(o: JSONObject?): GuestEntry? {
            val couch = o?.optString("couch")?.takeIf { Couches.isId(it) } ?: return null
            val who = o.optString("who")
            val side = SoloSide.fromJson(who, o.optJSONObject("side")) ?: return null
            return GuestEntry(couch, o.optString("couchName"), who, o.optString("whoName"), o.optString("day"), o.optString("game"), side, o.optBoolean("sent", false))
        }
    }
}

/**
 * The games played alone at friends' couches (2.0), kept on the phone apart from the home couch's state: a side played for Danny's
 * contest is Danny's couch's, and never touches the home's history, points, relay or notifications. Beside each: the contest as that
 * couch's TV last described it (who joined, who handed in, and, once this phone's side is in, the results).
 */
class GuestSolo(context: Context, name: String = "guest_solo") {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun all(): JSONObject = runCatching { JSONObject(prefs.getString("entries", "{}")!!) }.getOrElse { JSONObject() }

    fun entries(): List<GuestEntry> = all().let { o -> o.keys().asSequence().toList().mapNotNull { GuestEntry.fromJson(o.optJSONObject(it)) } }.sortedBy { it.side.t }

    fun entry(couch: String, day: String, game: String): GuestEntry? = GuestEntry.fromJson(all().optJSONObject("$couch|$day|$game"))

    /** The entries of [couch] its TV has not said it keeps yet. */
    fun pending(couch: String): List<GuestEntry> = entries().filter { it.couch == couch && !it.sent }

    fun pending(): List<GuestEntry> = entries().filter { !it.sent }

    /** [e] kept (the newer side of a game and a day wins), to be sent. True when it was kept. */
    fun put(e: GuestEntry): Boolean {
        val all = all()
        val cur = GuestEntry.fromJson(all.optJSONObject(e.key))
        if (cur != null && cur.side.t >= e.side.t) return false
        all.put(e.key, e.copy(sent = false).toJson())
        write(all)
        return true
    }

    /** The TV keeps the side written at [t] (a newer one written since still waits). */
    fun markSent(couch: String, day: String, game: String, t: Long) {
        val all = all()
        val cur = GuestEntry.fromJson(all.optJSONObject("$couch|$day|$game")) ?: return
        if (cur.sent || cur.side.t > t) return
        all.put(cur.key, cur.copy(sent = true).toJson())
        write(all)
    }

    private fun write(all: JSONObject) {
        prefs.edit { putString("entries", all.toString()) }
        bump()
    }

    /** The contests of [couch] on [day] as its TV described them: game -> who -> side. */
    fun setContest(couch: String, day: String, games: JSONObject) {
        if (!Couches.isId(couch)) return
        prefs.edit { putString("contest|$couch|$day", games.toString()) }
        bump()
    }

    /** The sides of [game] at [couch] on [day], as its TV last described them (the others' sealed until this phone's side was in). */
    fun contest(couch: String, day: String, game: String): List<SoloSide> {
        val g = runCatching { JSONObject(prefs.getString("contest|$couch|$day", "{}")!!) }.getOrElse { JSONObject() }.optJSONObject(game) ?: return emptyList()
        return g.keys().asSequence().toList().mapNotNull { SoloSide.fromJson(it, g.optJSONObject(it)) }.sortedBy { it.t }
    }

    /** The games with a contest at [couch] on [day], as its TV last described them. */
    fun contests(couch: String, day: String): List<String> =
        runCatching { JSONObject(prefs.getString("contest|$couch|$day", "{}")!!) }.getOrElse { JSONObject() }.keys().asSequence().toList()

    /** Days before [keepFrom] are let go of: what was sent, and the contests. What still waits to be sent stays until it went. */
    fun prune(keepFrom: String) {
        val all = all()
        var changed = false
        for (k in all.keys().asSequence().toList()) {
            val e = GuestEntry.fromJson(all.optJSONObject(k))
            if (e == null || (e.sent && e.day < keepFrom)) { all.remove(k); changed = true }
        }
        prefs.edit {
            if (changed) putString("entries", all.toString())
            for (k in prefs.all.keys) if (k.startsWith("contest|") && k.substringAfterLast('|') < keepFrom) remove(k)
        }
    }

    /** Everything of [couch] is let go of (it was removed from the list, and nothing of it waits to be sent). */
    fun forget(couch: String) {
        val all = all()
        for (k in all.keys().asSequence().toList()) if (k.startsWith("$couch|")) all.remove(k)
        prefs.edit {
            putString("entries", all.toString())
            for (k in prefs.all.keys) if (k.startsWith("contest|$couch|")) remove(k)
        }
        bump()
    }

    companion object {
        private val _changes = MutableStateFlow(0)

        /** Counts every change to what is kept here (a side written, sent, a contest described): the screens showing it read it again. */
        val changes: StateFlow<Int> get() = _changes

        private fun bump() { _changes.value = _changes.value + 1 }
    }
}
