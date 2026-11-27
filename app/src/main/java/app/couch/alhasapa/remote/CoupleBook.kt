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
}
