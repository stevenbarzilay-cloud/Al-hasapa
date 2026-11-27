package app.couch.alhasapa.results

import app.couch.alhasapa.catalog.Game
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.data.PieceStyle
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.data.Solo
import app.couch.alhasapa.data.SoloSide
import app.couch.alhasapa.data.StateRepository
import org.json.JSONObject

/** One person's line on the solo card: their piece and name, and their result (empty while sealed, or played without one). */
data class SoloLine(val who: String, val name: String, val piece: PieceStyle, val result: String, val sealed: Boolean, val hasResult: Boolean)

/**
 * What a game played alone came to (1.11), for the card shown when its result is saved: [mine] and the others who played that day's
 * game alone. With a [winner] ("t" for level) it is the reveal; without, the result is sealed in its envelope, waiting for the others.
 */
data class SoloOutcome(
    val gameName: String, val mine: SoloLine, val others: List<SoloLine>, val winner: String?,
    /** Seen by one of the players ([mine] is theirs); false on the TV's home, where the reveal is everyone's. */
    val viewer: Boolean = true,
    /** The couch the contest is at, by name (2.0): said on the card, so it is clear where the result counts. */
    val couch: String? = null,
    /** At a friend's couch: the result is kept on this phone and has not reached that couch's TV yet. */
    val pending: Boolean = false,
    /** Those who joined and have not handed in yet, by name (with more than two, the crown waits for them). */
    val waiting: List<String> = emptyList(),
    /** Those level at the top, by name, when it is a tie between some of several. */
    val level: List<String> = emptyList(),
) {
    val revealed: Boolean get() = winner != null
}

/** Writes the solo side of whoever holds the phone, the way the rest of the app reads it (see [Solo]). */
object SoloPlay {
    /** [who]'s result of [game] on [day], played alone: the result read from [input], or "played" when there is none. */
    fun save(repo: StateRepository, game: Game, day: String, who: String, input: ResultInput, now: Long = System.currentTimeMillis()): SoloSide {
        val draft = ResultDraft.evaluate(game, input.text, input.manual, input.fail, repo.snapshot().optJSONObject("log") ?: JSONObject(), day)
        val r = draft.result
        val side = if (r == null) SoloSide(who, null, t = now)
        else SoloSide(who, if (r.fail) null else r.v, r.max, r.fail, r.kind, now)
        repo.update { s -> Solo.put(s, day, game.id, side, now) }
        return side
    }

    /** Marks [game] as played alone today by [who] (after a while in it), unless there is a side already. True when it marked. */
    fun markPlayed(repo: StateRepository, game: Game, day: String, who: String, now: Long = System.currentTimeMillis()): Boolean {
        var marked = false
        repo.update { s -> if (Solo.side(s, day, game.id, who) == null) marked = Solo.put(s, day, game.id, SoloSide(who, null, t = now), now) }
        return marked
    }

    /** The result goes; "played" stays (newer, so it travels and replaces the result everywhere). */
    fun clear(repo: StateRepository, game: Game, day: String, who: String, now: Long = System.currentTimeMillis()) {
        repo.update { s -> if (Solo.side(s, day, game.id, who) != null) Solo.put(s, day, game.id, SoloSide(who, null, t = now), now) }
    }

    /** [who] leaves the contest without a result (2.0): the others are not kept waiting for them. */
    fun withdraw(repo: StateRepository, game: Game, day: String, who: String, now: Long = System.currentTimeMillis()) {
        repo.update { s -> if (Solo.side(s, day, game.id, who)?.hasResult == false) Solo.put(s, day, game.id, SoloSide(who, null, t = now, out = true), now) }
    }

    /** The kind the sides are compared in: theirs when they agree (a score read off an end screen, say), else the game's own. */
    fun kindFor(state: JSONObject, game: Game, day: String): String = kindFor(Solo.sides(state, day, game.id), game)

    fun kindFor(sides: List<SoloSide>, game: Game): String {
        val kinds = sides.filter { it.hasResult }.map { it.kind }.filter { it.isNotEmpty() }.toSet()
        return kinds.singleOrNull() ?: ResultDraft.kindOf(game)
    }

    /**
     * The TV's home (1.11): the first game of [day] that at least two played alone with a result, not yet revealed on this TV ([seen]),
     * with its reveal: everyone's result, the first to play first. With more than two in it (2.0) the reveal waits until everyone
     * who joined handed in (or left): a result on the TV's screen is seen by the whole room.
     */
    fun tvReveal(state: JSONObject, games: List<Game>, day: String, pieces: PiecesConfig, seen: (String) -> Boolean): Pair<String, SoloOutcome>? {
        for (game in games) {
            if (seen(game.id)) continue
            val kind = kindFor(state, game, day)
            val all = Solo.sides(state, day, game.id)
            val winner = Solo.winnerOf(all, kind, Solo.over(day)) ?: continue
            val lines = all.filter { it.hasResult }.map { s ->
                SoloLine(s.who, Couple.name(state, s.who), pieces.styleOf(Couple.person(state, s.who)), Solo.fmt(kind, s), sealed = false, hasResult = true)
            }
            val level = if (winner == "t") Solo.leadersOf(all, kind, Solo.over(day)).map { Couple.name(state, it) } else emptyList()
            return game.id to SoloOutcome(game.n, lines.first(), lines.drop(1), winner, viewer = false, level = level.takeIf { lines.size > 2 }.orEmpty())
        }
        return null
    }

    /** The card for [me] after their side of [game] on [day] was saved: sealed, or the reveal. Null when [me] has no side. */
    fun outcome(state: JSONObject, game: Game, day: String, me: String, pieces: PiecesConfig): SoloOutcome? =
        outcome(Solo.sides(state, day, game.id), game, day, me, { Couple.name(state, it) }, { pieces.styleOf(Couple.person(state, it)) })

    /**
     * As above, from the sides themselves ([all]: as this device has them; at a friend's couch, as its TV described them, the
     * others' sealed until this phone's side was in), with the players' names and pieces as their couch has them.
     */
    fun outcome(all: List<SoloSide>, game: Game, day: String, me: String, name: (String) -> String, piece: (String) -> PieceStyle): SoloOutcome? {
        val kind = kindFor(all, game)
        val sides = Solo.sealedFor(all, me)
        val mineSide = sides.firstOrNull { it.who == me } ?: return null
        val real = all.associateBy { it.who }
        // A sealed line still says whether there is a result in the envelope (not what it is).
        fun line(s: SoloSide, sealed: Boolean) = SoloLine(
            s.who, name(s.who), piece(s.who), if (sealed || s.env) "" else Solo.fmt(kind, s), sealed || s.env,
            real[s.who]?.let { it.hasResult || it.env } == true,
        )
        val open = mineSide.hasResult
        // Those who left the contest are not in it; the phone's holder still sees their own line.
        val others = sides.filter { it.who != me && !it.out }
        val over = Solo.over(day)
        val winner = if (open) Solo.winnerOf(all, kind, over) else null
        return SoloOutcome(
            game.n, line(mineSide, false), others.map { line(it, !open) }, winner,
            waiting = if (open && winner == null) Solo.waiting(others).map { name(it.who) } else emptyList(),
            level = if (winner == "t" && others.size > 1) Solo.leadersOf(all, kind, over).map { name(it) } else emptyList(),
        )
    }
}
