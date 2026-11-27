package app.couch.alhasapa.results

import app.couch.alhasapa.catalog.Game
import app.couch.alhasapa.scoring.Parsed
import app.couch.alhasapa.scoring.Result
import app.couch.alhasapa.scoring.Scoring
import org.json.JSONObject

/** What the person typed, pasted or ticked in the result sheet. */
data class ResultInput(
    /** The share text the game copied (or what they pasted). */
    val text: String,
    /** A value typed by hand: a number, or a time like 1:24. */
    val manual: String,
    /** "We didn't make it this time". */
    val fail: Boolean,
    /** Together games only: who cracked it ("a", "b", "t" for both), or null to leave it as it is. */
    val credit: String?,
)

/** The sheet's logic, ported from the prototype's `openResult` / `openDuel` (`current()`, `readManual`). No Android in here. */
object ResultDraft {
    /** Games with no metric of their own are entered as guesses, as in the prototype. */
    fun kindOf(game: Game): String = if (game.metric == "none") "guesses" else game.metric

    /** The prototype's readManual: a time for time games, otherwise whatever number the field starts with. */
    fun readManual(kind: String, text: String): Parsed? {
        val v = text.trim()
        if (v.isEmpty()) return null
        if (kind == "time") return Scoring.parseResult(v, "time")
        if (kind == "score") Scoring.parseResult(v, "score")?.let { return it }
        val n = FLOAT.find(v)?.value?.toDoubleOrNull() ?: return null
        return Parsed(n, if (kind == "guesses") 6 else null, false)
    }

    // JavaScript's parseFloat: the longest number at the start of the string.
    private val FLOAT = Regex("^[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    data class Draft(
        /** Null when nothing was recognised or typed. */
        val parsed: Parsed?,
        val result: Result?,
        /** Couch points this entry is worth (2 for playing + the result), as the hub's preview shows them. */
        val points: Int,
    )

    fun evaluate(game: Game, text: String, manual: String, fail: Boolean, log: JSONObject, day: String): Draft {
        var kind = kindOf(game)
        var p = Scoring.parseResult(text, kind) ?: readManual(kind, manual)
        // Nothing in the game's own kind, but the end screen said how we did against the others (HebrewResult, 1.9.10): that is the
        // result then, out of a hundred (better than 82% of the players is 8 points of 10).
        if (p == null) HebrewResult.beatIn(text)?.let { b -> p = Parsed(b.toDouble(), 100, false); kind = "score" }
        if (fail) p = Parsed(v = p?.v?.takeIf { !it.isNaN() && it != 0.0 } ?: 0.0, max = p?.max, fail = true)
        val parsed = p ?: return Draft(null, null, 0)
        val r = Result(v = parsed.v, max = parsed.max, fail = parsed.fail, kind = kind, raw = text.trim().take(MAX_RAW))
        return Draft(parsed, r, 2 + Scoring.resultPts(log, game.id, r, day))
    }

    const val MAX_RAW = 400
}
