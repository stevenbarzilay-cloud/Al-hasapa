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

    /** The kind the sides are compared in: theirs when they agree (a score read off an end screen, say), else the game's own. */
    fun kindFor(state: JSONObject, game: Game, day: String): String {
        val kinds = Solo.sides(state, day, game.id).filter { it.hasResult }.map { it.kind }.filter { it.isNotEmpty() }.toSet()
        return kinds.singleOrNull() ?: ResultDraft.kindOf(game)
    }

    /**
     * The TV's home (1.11): the first game of [day] that at least two played alone with a result, not yet revealed on this TV ([seen]),
     * with its reveal: everyone's result, the first to play first.
     */
    fun tvReveal(state: JSONObject, games: List<Game>, day: String, pieces: PiecesConfig, seen: (String) -> Boolean): Pair<String, SoloOutcome>? {
        for (game in games) {
            if (seen(game.id)) continue
            val kind = kindFor(state, game, day)
            val winner = Solo.winner(state, day, game.id, kind) ?: continue
            val lines = Solo.sides(state, day, game.id).filter { it.hasResult }.map { s ->
                SoloLine(s.who, Couple.name(state, s.who), pieces.styleOf(Couple.person(state, s.who)), Solo.fmt(kind, s), sealed = false, hasResult = true)
            }
            return game.id to SoloOutcome(game.n, lines.first(), lines.drop(1), winner, viewer = false)
        }
        return null
    }

    /** The card for [me] after their side of [game] on [day] was saved: sealed, or the reveal. Null when [me] has no side. */
    fun outcome(state: JSONObject, game: Game, day: String, me: String, pieces: PiecesConfig): SoloOutcome? {
        val kind = kindFor(state, game, day)
        val sides = Solo.sealedFor(state, day, game.id, me)
        val real = Solo.sides(state, day, game.id).associateBy { it.who }
        val mineSide = sides.firstOrNull { it.who == me } ?: return null
        // A sealed line still says whether there is a result in the envelope (not what it is).
        fun line(s: SoloSide, sealed: Boolean) = SoloLine(
            s.who, Couple.name(state, s.who), pieces.styleOf(Couple.person(state, s.who)),
            if (sealed) "" else Solo.fmt(kind, s), sealed, real[s.who]?.hasResult == true,
        )
        val open = mineSide.hasResult
        return SoloOutcome(
            game.n, line(mineSide, false),
            sides.filter { it.who != me }.map { line(it, !open) },
            if (open) Solo.winner(state, day, game.id, kind) else null,
        )
    }
}
