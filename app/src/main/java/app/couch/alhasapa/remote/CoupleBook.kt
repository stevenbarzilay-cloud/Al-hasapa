package app.couch.alhasapa.remote

import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.data.Note
import app.couch.alhasapa.data.Notes
import app.couch.alhasapa.data.Person
import app.couch.alhasapa.data.StateRepository

/**
 * What the link needs of the state on each side (1.9.9): both of us as this device knows us, the newer word from the other side, and
 * (on the TV) the notes the phones send.
 */
interface CoupleBook {
    /** Both of us, the first then the second, then the others (removed ones and past guests too, as the other devices need them). */
    fun couple(): List<Person>

    /** What the other side knows of us: kept where newer, person by person. True when anything changed here. */
    fun offer(people: List<Person>): Boolean

    /** A note from a phone, kept once. True when it is new. */
    fun note(n: Note): Boolean

    /**
     * What this device shares with the others (1.12, see [app.couch.alhasapa.data.Shared]), in messages small enough for the link: the
     * evenings of the last [days] days (0: every evening it keeps), then the library, our links, our games, the jokes and the looks.
     */
    fun shares(days: Int): List<org.json.JSONObject> = emptyList()

    /** Another device's share: kept where newer. True when anything here changed. */
    fun takeShare(s: org.json.JSONObject): Boolean = false

    /** The couch this device is of (2.0), or null while it has none yet. */
    fun couch(): app.couch.alhasapa.data.CouchRef? = null

    /** A new device, nothing chosen on it yet (2.0): it has nothing of its own to tell, and takes the couch it joins. */
    fun fresh(): Boolean = false

    /** This device's couch has nothing in it yet (2.0, see [app.couch.alhasapa.data.Couches.bare]). */
    fun bare(): Boolean = false

    /**
     * On a TV whose couch has nothing in it (2.0): [s], a share of another couch from a phone of a home, is taken whole, the couch with
     * it, in place of the empty one. True when it was (the couch was still bare, and [s] named another).
     */
    fun adopt(s: org.json.JSONObject): Boolean = false

    /**
     * On the TV (2.0): a guest's own side of a game played alone for this couch's contest, kept where newer. True when the couch has
     * it now (kept this time, or already there), so the guest's phone can stop keeping it to send.
     */
    fun guestSide(day: String, game: String, side: app.couch.alhasapa.data.SoloSide): Boolean = false

    /** On the TV: the games played alone here on [day] as [who] may see them: game -> who -> side, the others' sealed until [who] handed in. */
    fun contest(day: String, who: String): org.json.JSONObject = org.json.JSONObject()
}

/** The app's own state ([repo] null: none yet, so nothing is known or kept). */
class RepoCoupleBook(private val repo: () -> StateRepository?) : CoupleBook {
    override fun couple(): List<Person> {
        val s = repo()?.snapshot() ?: return emptyList()
        return Couple.all(s)
    }

    override fun offer(people: List<Person>): Boolean {
        val r = repo() ?: return false
        val now = r.snapshot()
        if (people.none { it.at > Couple.stamp(now, it.who) }) return false
        // What another device knows: merged, not a change made here (it is not stamped anew, nor told on).
        return r.mergeIf { s -> people.map { Couple.offer(s, it) }.any { it } }
    }

    override fun note(n: Note): Boolean {
        val r = repo() ?: return false
        if (Notes.all(r.snapshot()).any { it.id == n.id }) return false
        return r.mergeIf { s -> Notes.add(s, n) }
    }

    override fun shares(days: Int): List<org.json.JSONObject> {
        val s = repo()?.snapshot() ?: return emptyList()
        val which = if (days <= 0) app.couch.alhasapa.data.Shared.days(s) else ShareBus.lastDays(days)
        return app.couch.alhasapa.data.Shared.chunks(s, which)
    }

    override fun takeShare(s: org.json.JSONObject): Boolean = repo()?.mergeShared(s) ?: false

    override fun couch(): app.couch.alhasapa.data.CouchRef? = repo()?.peek { app.couch.alhasapa.data.Couches.of(it) }

    override fun fresh(): Boolean = repo()?.peek { app.couch.alhasapa.data.Couches.needsSetup(it) } ?: true

    override fun bare(): Boolean = repo()?.peek { app.couch.alhasapa.data.Couches.bare(it) } ?: false

    override fun adopt(s: org.json.JSONObject): Boolean {
        val r = repo() ?: return false
        return r.mergeIf { st ->
            if (!app.couch.alhasapa.data.Couches.bare(st) || !app.couch.alhasapa.data.Couches.foreign(st, s)) return@mergeIf false
            // The empty couch goes (its id, and the names nobody gave); the phone's comes in its place, with everything in it.
            st.remove(app.couch.alhasapa.data.Couches.KEY); st.remove("idAt")
            app.couch.alhasapa.data.Shared.merge(st, s, r.clock())
            true
        }
    }

    override fun guestSide(day: String, game: String, side: app.couch.alhasapa.data.SoloSide): Boolean {
        val r = repo() ?: return false
        // Only the days a contest can still be handed in for: a week back at most, never ahead.
        val today = java.time.LocalDate.now()
        if (day > app.couch.alhasapa.data.CouchState.dayKey(today.plusDays(1)) || day < app.couch.alhasapa.data.CouchState.dayKey(today.minusDays(GUEST_DAYS))) return false
        // A change made here, as far as the home's own phones go: they hear of it like of any other (the link, the relay).
        r.update { s -> app.couch.alhasapa.data.Solo.put(s, day, game, side) }
        return r.peek { s -> app.couch.alhasapa.data.Solo.side(s, day, game, side.who)?.let { it.t >= side.t } == true }
    }

    override fun contest(day: String, who: String): org.json.JSONObject {
        val s = repo()?.snapshot() ?: return org.json.JSONObject()
        val out = org.json.JSONObject()
        for (game in app.couch.alhasapa.data.Solo.games(s, day)) {
            val g = org.json.JSONObject()
            for (side in app.couch.alhasapa.data.Solo.sealedFor(s, day, game, who)) g.put(side.who, side.toJson())
            if (g.length() > 0) out.put(game, g)
        }
        return out
    }

    companion object {
        /** A guest's side of a game played alone is taken for this many days back. */
        const val GUEST_DAYS = 7L
    }
}
