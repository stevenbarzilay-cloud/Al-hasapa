package app.couch.alhasapa.remote

import android.content.Context
import app.couch.alhasapa.BuildConfig
import app.couch.alhasapa.data.CouchRef
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.data.Person
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.diag.Flight
import java.io.File

/**
 * Couches on this device (2.0): beginning one, sorting what the device knew before 2.0 into them, visiting a friend's, and changing
 * which one is the home. Nothing here asks the person anything; the screens do, and call these.
 */
object CouchSetup {
    /**
     * A device from before 2.0 (it has a state and no couch) is given its couch, without a question where that is safe. The TV
     * names its couch (from the topic of the solo relay it made, when it made one). A phone that was paired with one TV is of that
     * TV's couch: it learns the id from it when they next meet (at home, or through the relay), and until then its couch is simply
     * unnamed. A phone that knew several TVs takes the couch of the one that gave it the relay it holds (the same id, from the
     * same topic, without their talking); with no relay to tell by, it asks once ([CouchStore.askHome]). The state itself is not
     * touched meanwhile, whatever happens: names, points, history, logins and pairings stay as they are.
     */
    fun migrate(context: Context, repo: StateRepository, tv: Boolean = BuildConfig.TV) {
        val couches = CouchStore(context)
        val has = repo.peek { Couches.of(it) != null }
        if (has) { if (!couches.migrated) { if (!tv && couches.homeTvs.isEmpty() && couches.askHome.isEmpty()) sortTvs(context, couches, known = true); couches.migrated = true }; return }
        if (couches.migrated || repo.peek { Couches.needsSetup(it) }) return
        val topic = RelayStore(context).config?.topic
        if (tv) {
            val id = topic?.let { Couches.idFromTopic(it) } ?: Couches.newId()
            repo.update { s -> Couches.set(s, id, "", 0L) }
            Flight.log("couches: this TV's couch from before 2.0 has its id now${if (topic != null) " (from the relay's topic)" else ""}")
        } else {
            val tvs = RemoteStore(context).knownTvs()
            when {
                tvs.isEmpty() -> repo.update { s -> Couches.set(s, Couches.newId(), "", 0L) }
                tvs.size == 1 -> sortTvs(context, couches, known = false)
                topic != null -> { repo.update { s -> Couches.set(s, Couches.idFromTopic(topic), "", 0L) }; sortTvs(context, couches, known = true) }
                else -> sortTvs(context, couches, known = false)
            }
            Flight.log("couches: this phone's data from before 2.0 is its home couch's (${tvs.size} TVs known${if (topic != null) ", id from the relay's topic" else ""})")
        }
        couches.migrated = true
    }

    /**
     * The TVs the phone was paired with before 2.0. With the home's id [known], each is taken for a TV of the home until it says it is
     * of another couch (see [otherCouch]); without it, one TV is the home's, and of several the person is asked.
     */
    private fun sortTvs(context: Context, couches: CouchStore, known: Boolean) {
        val tvs = RemoteStore(context).knownTvs().toSet() - couches.saved().flatMap { it.tvs }.toSet()
        if (tvs.isEmpty()) return
        if (known || tvs.size == 1) couches.homeTvs = couches.homeTvs + tvs else couches.askHome = tvs
    }

    /** A new couch begun on this device: its name, the two players' names, nothing played. */
    fun create(context: Context, repo: StateRepository, couchName: String, a: String, b: String, now: Long = System.currentTimeMillis()): CouchRef {
        val id = Couches.newId()
        repo.update { s ->
            val na = a.trim().take(Couple.MAX_NAME).ifEmpty { CouchState.DEFAULT_A }
            val nb = b.trim().take(Couple.MAX_NAME).ifEmpty { CouchState.DEFAULT_B }
            // (A name nobody gave, a TV's couch begun with one press, is not stamped: the first phone's names win over it.)
            Couple.put(s, Person("a", na, "", "", if (a.isBlank()) 0L else now))
            Couple.put(s, Person("b", nb, "", "", if (b.isBlank()) 0L else now))
            Couches.set(s, id, couchName.trim(), if (couchName.isBlank()) 0L else now)
        }
        CouchStore(context).migrated = true
        Flight.log("couches: a new couch was begun here")
        return repo.peek { Couches.of(it)!! }
    }

    /** The phone's answer to "is this TV your home's?" (a phone from before 2.0 that knew several). */
    fun answerHome(context: Context, tv: KeyClient.Tv, home: Boolean) {
        val couches = CouchStore(context)
        couches.askHome = couches.askHome - tv.id
        if (home) {
            couches.homeTvs = couches.homeTvs + tv.id
            // The others it knew are not its home's: each is met again as a friend's, when the person chooses it.
            couches.askHome = emptySet()
            Flight.log("couches: the person said which TV is their home's")
        } else {
            tv.couch?.let { c -> couches.save((couches.saved(c.id) ?: SavedCouch(c.id, c.name)).let { it.copy(name = c.name, tvs = (it.tvs + tv.id).distinct(), at = System.currentTimeMillis()) }) }
        }
    }

    /**
     * A TV taken for the home's turned out to be of another couch: it is the home's no longer, and is kept among the friends'
     * couches (the phone was paired with it before 2.0), to be visited when the person chooses.
     */
    fun otherCouch(context: Context, tv: KeyClient.Tv) {
        val couches = CouchStore(context)
        if (tv.id !in couches.homeTvs) return
        couches.homeTvs = couches.homeTvs - tv.id
        val c = tv.couch ?: return
        couches.save((couches.saved(c.id) ?: SavedCouch(c.id, c.name)).let { it.copy(name = c.name, tvs = (it.tvs + tv.id).distinct(), at = System.currentTimeMillis()) })
        Flight.log("couches: a TV known before 2.0 is of another couch; kept among the friends' couches")
    }

    /**
     * A visit begins: the phone's holder is a guest at [couch] through [tv], as [me] (a player of that couch). [kept]: the couch is
     * saved for the next visits. The home couch is not touched.
     */
    fun startVisit(
        context: Context, couch: CouchRef, tv: String, me: Person, kept: Boolean, evening: String = Couple.evening(), now: Long = System.currentTimeMillis(),
        /** [me] was added there by this phone just now (a guest, or someone who comes often), not chosen among that couch's players. */
        added: Boolean = false,
    ): Visit {
        val couches = CouchStore(context)
        val was = couches.saved(couch.id)
        couches.save(SavedCouch(
            couch.id, couch.name, ((was?.tvs ?: emptyList()) + tv).distinct(), me.who, me.name, kept = kept || was?.kept == true, at = now,
            archived = was?.archived == true, made = if (added) me else was?.made?.takeIf { it.who == me.who },
        ))
        val v = Visit(couch.id, couch.name, tv, me.who, me.name, evening, now)
        couches.visit = v
        Flight.log("couches: a visit began (${if (kept) "the couch is saved" else "for this evening"})")
        return v
    }

    /** The visit is over: the phone is at home again. A couch that was for the evening only is forgotten, its pairing too. */
    fun endVisit(context: Context): Visit? {
        val v = CouchStore(context).endVisit() ?: return null
        Flight.log("couches: the visit is over")
        return v
    }

    /** A friend's couch is taken off the list here (it stays as it is at its own home): its pairings and what was kept of it go. */
    fun remove(context: Context, id: String) {
        val couches = CouchStore(context)
        val c = couches.saved(id) ?: return
        val remote = RemoteStore(context)
        for (tv in c.tvs) remote.forgetTv(tv)
        couches.remove(id)
        GuestSolo(context).forget(id)
        archive(context, id).delete()
        Flight.log("couches: a couch was taken off the list")
    }

    private fun archive(context: Context, id: String): File = File(File(context.filesDir, "couches"), "$id.json")

    /**
     * Another couch becomes the phone's home (asked for in so many words, and confirmed, on the "my couches" screen). The home that
     * was is kept whole where it is (on its TV, on its other phones) and here too, among the saved couches; nothing of it moves into
     * the new home, and nothing of the new home is known yet but its id and name: its own TV hands over the rest once the phone is
     * let in there as one of its own (with the code on that TV).
     */
    fun setHome(context: Context, repo: StateRepository, to: CouchRef, tvs: Collection<String>, now: Long = System.currentTimeMillis()) {
        val couches = CouchStore(context)
        val remote = RemoteStore(context)
        // (A state from before 2.0 that has not learnt its couch's id yet is a couch all the same: it is given one, and kept.)
        if (repo.peek { Couches.of(it) == null && !Couches.needsSetup(it) }) repo.update { s -> Couches.set(s, Couches.newId(), "", 0L) }
        val was = repo.peek { Couches.of(it) }
        if (was?.id == to.id) return
        // The home that was: its state kept here, and listed among the saved couches with who the phone's holder was there.
        if (was != null) {
            archive(context, was.id).apply { parentFile?.mkdirs() }.writeText(repo.json())
            val me = remote.me
            couches.save(SavedCouch(was.id, was.name, couches.homeTvs.toList(), me, me?.let { w -> repo.peek { s -> Couple.name(s, w) } }, kept = true, at = now, archived = true))
        }
        // The new home: what was kept of it here if it was the home before; else only its id and name.
        val saved = couches.saved(to.id)
        val file = archive(context, to.id)
        val kept = if (file.isFile) CouchState.parse(runCatching { file.readText() }.getOrNull())?.takeIf { Couches.of(it)?.id == to.id } else null
        val next = kept ?: CouchState.empty().also { Couches.set(it, to.id, "", 0L) }
        repo.replace(next.toString())
        file.delete()
        couches.remove(to.id)
        couches.homeTvs = (tvs + (saved?.tvs ?: emptyList())).filter { it.isNotEmpty() }.toSet()
        couches.askHome = emptySet()
        couches.visit = null
        // Who holds the phone is a matter of each couch: asked again at the new home (or as it was, at one that was the home before).
        remote.me = if (kept != null) saved?.me else null
        // The home's own channel is the new home's: its TV hands it over. Nothing more is read from, or said to, the old one's.
        RelayStore(context).forgetChannel()
        for (tv in couches.homeTvs) remote.clearSharedAll(tv)
        Flight.log("couches: the home couch was changed; the one before is kept among the saved couches")
    }
}
