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
    /** Both of us, the first then the second. */
    fun couple(): List<Person>

    /** What the other side knows of us: kept where newer, person by person. True when anything changed here. */
    fun offer(people: List<Person>): Boolean

    /** A note from a phone, kept once. True when it is new. */
    fun note(n: Note): Boolean
}

/** The app's own state ([repo] null: none yet, so nothing is known or kept). */
class RepoCoupleBook(private val repo: () -> StateRepository?) : CoupleBook {
    override fun couple(): List<Person> {
        val s = repo()?.snapshot() ?: return emptyList()
        return Couple.WHO.map { Couple.person(s, it) }
    }

    override fun offer(people: List<Person>): Boolean {
        val r = repo() ?: return false
        val now = r.snapshot()
        if (people.none { it.at > Couple.stamp(now, it.who) }) return false
        var changed = false
        r.update { s -> for (p in people) if (Couple.offer(s, p)) changed = true }
        return changed
    }

    override fun note(n: Note): Boolean {
        val r = repo() ?: return false
        if (Notes.all(r.snapshot()).any { it.id == n.id }) return false
        var added = false
        r.update { s -> added = Notes.add(s, n) }
        return added
    }
}
