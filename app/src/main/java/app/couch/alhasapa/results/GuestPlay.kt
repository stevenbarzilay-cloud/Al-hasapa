package app.couch.alhasapa.results

import android.content.Context
import app.couch.alhasapa.catalog.Game
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.data.Person
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.data.SoloSide
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.remote.CouchStore
import app.couch.alhasapa.remote.GuestEntry
import app.couch.alhasapa.remote.GuestSolo
import app.couch.alhasapa.remote.Links
import app.couch.alhasapa.remote.RemoteStore
import app.couch.alhasapa.remote.SoloTarget
import org.json.JSONObject

/**
 * Where a game played alone goes (2.0). With no visit on: the home couch, and nothing is asked. During a visit the person is asked
 * before the game opens, for every new game played alone; and once a game of a puzzle's day was said to go somewhere, it goes there
 * whatever happens after (the visit ends, the network drops, the result is typed the next day): the choice is kept with the game.
 */
object SoloTargets {
    sealed interface Need {
        /** It goes to [t]: nothing to ask. */
        data class Go(val t: SoloTarget) : Need
        /** A visit is on, and this game was not played alone yet: the person says which couch. */
        data class Ask(val home: SoloTarget, val visit: SoloTarget) : Need
        /** Nobody was said to hold this phone at home yet: that is asked first. */
        data object Who : Need
    }

    /** The home couch, with whoever holds the phone there (null when that was not said, or they are a guest of another evening). */
    fun home(context: Context, repo: StateRepository): SoloTarget? {
        val me = RemoteStore(context).me ?: return null
        return repo.peek { s ->
            if (Couple.everyone(s, Couple.evening()).none { it.who == me }) null
            else SoloTarget(Couches.of(s)?.id.orEmpty(), Couches.name(s), me, Couple.name(s, me), home = true)
        }
    }

    /** The couch being visited this evening, with the player the phone's holder is there. */
    fun visit(context: Context): SoloTarget? =
        CouchStore(context).visitNow()?.let { v -> SoloTarget(v.couch, v.name, v.me, v.meName, home = false) }

    /** Whether [t] is the home couch as it is now (the home may have been changed since [t] was chosen). */
    fun isHome(t: SoloTarget, repo: StateRepository): Boolean = t.couch == repo.peek { Couches.of(it)?.id.orEmpty() }

    fun resolve(context: Context, repo: StateRepository, day: String, game: String): Need {
        CouchStore(context).target(day, game)?.let { return Need.Go(it) }
        val home = home(context, repo) ?: return Need.Who
        val visit = visit(context) ?: return Need.Go(home)
        return Need.Ask(home, visit)
    }

    /** [t] is where [game] of [day] goes from now on (kept with it; days before yesterday are let go of). */
    fun choose(context: Context, day: String, game: String, t: SoloTarget) {
        val keep = java.time.LocalDate.parse(day).minusDays(7).toString()
        CouchStore(context).setTarget(day, game, t, keepFrom = keep)
    }
}

/**
 * A game played alone for a friend's couch's contest (2.0): the same sides the home's own contests are made of, kept on this phone
 * apart from the home's state ([GuestSolo]) and handed to that couch's TV. Nothing here touches the home couch.
 */
object GuestPlay {
    private fun store(context: Context) = GuestSolo(context)

    fun side(context: Context, game: Game, day: String, t: SoloTarget): SoloSide? = store(context).entry(t.couch, day, game.id)?.side

    private fun put(context: Context, game: Game, day: String, t: SoloTarget, side: SoloSide): Boolean {
        val kept = store(context).put(GuestEntry(t.couch, t.name, t.who, t.whoName, day, game.id, side, sent = false))
        // Sent now when that couch's TV is the one connected; else it waits here, and goes when it is reached again.
        if (kept) runCatching { Links.client(context).flushGuest() }
        return kept
    }

    /** The result read from [input] (or "played" when there is none), as the player [t] names, for that couch's contest. */
    fun save(context: Context, game: Game, day: String, t: SoloTarget, input: ResultInput, now: Long = System.currentTimeMillis()): SoloSide {
        val draft = ResultDraft.evaluate(game, input.text, input.manual, input.fail, JSONObject(), day)
        val r = draft.result
        val side = if (r == null) SoloSide(t.who, null, t = now) else SoloSide(t.who, if (r.fail) null else r.v, r.max, r.fail, r.kind, now)
        put(context, game, day, t, side)
        return side
    }

    /** Joined: played, no result yet (after a while in the game), unless there is a side already. True when it marked. */
    fun markPlayed(context: Context, game: Game, day: String, t: SoloTarget, now: Long = System.currentTimeMillis()): Boolean =
        if (side(context, game, day, t) != null) false else put(context, game, day, t, SoloSide(t.who, null, t = now))

    fun clear(context: Context, game: Game, day: String, t: SoloTarget, now: Long = System.currentTimeMillis()) {
        if (side(context, game, day, t) != null) put(context, game, day, t, SoloSide(t.who, null, t = now))
    }

    fun withdraw(context: Context, game: Game, day: String, t: SoloTarget, now: Long = System.currentTimeMillis()) {
        if (side(context, game, day, t)?.hasResult == false) put(context, game, day, t, SoloSide(t.who, null, t = now, out = true))
    }

    /**
     * The contest's card: this phone's own side, and the others as that couch's TV last described them. Until this phone's side has
     * reached the TV the others stay sealed (the TV opens them for a guest only once it has theirs), and the card says it waits.
     */
    fun outcome(context: Context, game: Game, day: String, t: SoloTarget, pieces: PiecesConfig): SoloOutcome? {
        val entry = store(context).entry(t.couch, day, game.id) ?: return null
        val told = store(context).contest(t.couch, day, game.id).filter { it.who != t.who }
        val people = CouchStore(context).people(t.couch).associateBy { it.who }
        fun name(who: String) = if (who == t.who) t.whoName else people[who]?.name?.ifEmpty { null } ?: "אורח"
        fun piece(who: String) = pieces.styleOf(people[who] ?: Person(who, name(who), "", "", 0L))
        // Until the TV has this side, what it told of the others is of before it: shown sealed, whatever was told.
        val others = if (entry.sent) told else told.map { it.sealed() }
        val o = SoloPlay.outcome(others + entry.side, game, day, t.who, ::name, ::piece) ?: return null
        return o.copy(couch = t.name, pending = !entry.sent, winner = if (entry.sent) o.winner else null)
    }
}
