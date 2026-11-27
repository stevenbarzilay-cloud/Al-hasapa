package app.couch.alhasapa.data

import app.couch.alhasapa.scoring.Result
import app.couch.alhasapa.scoring.Scoring
import org.json.JSONObject

/**
 * One side of a solo game (1.11): [who] played that day's game alone, on their own phone, and got this result ([v], out of [max],
 * [fail]ed), or only played it ([v] null, not failed: the result may come later). [t] is when the side was last written: the newest of
 * a side wins wherever two copies meet.
 */
data class SoloSide(
    val who: String,
    val v: Double?,
    val max: Int? = null,
    val fail: Boolean = false,
    val kind: String = "",
    val t: Long,
) {
    /** A result to compare (a number, or not solved), not only "played". */
    val hasResult: Boolean get() = v != null || fail

    fun toJson(): JSONObject = JSONObject().put("t", t).apply {
        if (v != null) put("v", app.couch.alhasapa.scoring.jsNumber(v)) else if (!fail) put("p", true)
        if (max != null) put("max", max)
        if (fail) put("fail", true)
        if (kind.isNotEmpty()) put("kind", kind)
    }

    /** As the result type the rest of the app formats and scores. */
    fun asResult(): Result? = if (!hasResult) null else Result(v ?: 0.0, max, fail, kind)

    companion object {
        private val KINDS = setOf("guesses", "mistakes", "hints", "time", "words", "score", "none")

        /** Null for anything that is not a side of one of ours (written by hand, by another version, or sent by someone else). */
        fun fromJson(who: String, o: JSONObject?): SoloSide? {
            if (o == null || !Couple.isId(who)) return null
            val t = o.optLong("t", 0L).takeIf { it > 0 } ?: return null
            val v = if (o.has("v") && !o.isNull("v")) o.optDouble("v", Double.NaN).takeIf { it.isFinite() && Math.abs(it) < 1e9 } ?: return null else null
            val max = if (o.has("max")) o.optInt("max", 0).takeIf { it in 1..100_000 } else null
            val kind = o.optString("kind").takeIf { it in KINDS }.orEmpty()
            return SoloSide(who, v, max, o.optBoolean("fail", false), kind, t)
        }
    }
}

/**
 * Solo games (1.11): one of us plays a game alone, on their own phone, and whoever plays the same day's game alone too is compared with
 * them; the couch's own points stay for the games played together. Kept in the state under "solo": day -> game -> who -> a side
 * ([SoloSide]). A side is written only on its person's phone and travels to the others (the relay, see remote/SoloRelay); until the
 * one looking has played that day's game alone too, the others' results stay sealed ([sealedFor]).
 */
object Solo {
    private val DAY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val GAME = Regex("[a-z0-9-]{1,40}")
    /** A side written more than a day ahead of this device's clock is not taken (it would win against every later change). */
    private const val AHEAD_MS = 24 * 60 * 60 * 1000L

    fun sides(state: JSONObject, day: String, game: String): List<SoloSide> {
        val g = state.optJSONObject("solo")?.optJSONObject(day)?.optJSONObject(game) ?: return emptyList()
        return g.keys().asSequence().toList().mapNotNull { SoloSide.fromJson(it, g.optJSONObject(it)) }.sortedBy { it.t }
    }

    fun side(state: JSONObject, day: String, game: String, who: String): SoloSide? = sides(state, day, game).firstOrNull { it.who == who }

    /** The games played alone on [day], by anyone. */
    fun games(state: JSONObject, day: String): List<String> =
        state.optJSONObject("solo")?.optJSONObject(day)?.keys()?.asSequence()?.filter { sides(state, day, it).isNotEmpty() }?.toList().orEmpty()

    /** [s] into [state] if it is newer than the side already there. True when anything changed. */
    fun put(state: JSONObject, day: String, game: String, s: SoloSide, now: Long = System.currentTimeMillis()): Boolean {
        if (!DAY.matches(day) || !GAME.matches(game) || !Couple.isId(s.who) || s.t > now + AHEAD_MS) return false
        val cur = side(state, day, game, s.who)
        if (cur != null && cur.t >= s.t) return false
        val solo = state.optJSONObject("solo") ?: JSONObject().also { state.put("solo", it) }
        val d = solo.optJSONObject(day) ?: JSONObject().also { solo.put(day, it) }
        val g = d.optJSONObject(game) ?: JSONObject().also { d.put(game, it) }
        g.put(s.who, s.toJson())
        return true
    }

    /** Another device's "solo" (or part of it) into [state], side by side, the newer winning. True when anything changed. */
    fun merge(state: JSONObject, incoming: JSONObject?, now: Long = System.currentTimeMillis()): Boolean {
        if (incoming == null) return false
        var changed = false
        for (day in incoming.keys().asSequence().toList()) {
            val d = incoming.optJSONObject(day) ?: continue
            for (game in d.keys().asSequence().toList()) {
                val g = d.optJSONObject(game) ?: continue
                for (who in g.keys().asSequence().toList()) {
                    val s = SoloSide.fromJson(who, g.optJSONObject(who)) ?: continue
                    if (put(state, day, game, s, now)) changed = true
                }
            }
        }
        return changed
    }

    /** [who]'s own sides on [days], as a "solo" object: what their phone tells the others. */
    fun mine(state: JSONObject, who: String, days: Collection<String>): JSONObject {
        val out = JSONObject()
        for (day in days) for (game in games(state, day)) {
            val s = side(state, day, game, who) ?: continue
            val d = out.optJSONObject(day) ?: JSONObject().also { out.put(day, it) }
            d.put(game, JSONObject().put(who, s.toJson()))
        }
        return out
    }

    /**
     * Whether [a] did better than [b] in a game of [kind]: positive if [a] did, negative if [b] did, 0 for as well (or for a game with
     * nothing to compare). Not solved is worse than any result; fewer guesses, mistakes, hints or seconds are better; more points
     * (out of the same whole, or as a share of it) and more words are better.
     */
    fun compare(kind: String, a: SoloSide, b: SoloSide): Int {
        if (a.fail || b.fail) return if (a.fail && b.fail) 0 else if (a.fail) -1 else 1
        val va = a.v ?: return if (b.v == null) 0 else -1
        val vb = b.v ?: return 1
        return when (kind) {
            "none" -> 0
            "score" -> {
                val x = if (a.max != null && b.max != null && a.max != b.max) va / a.max else va
                val y = if (a.max != null && b.max != null && a.max != b.max) vb / b.max else vb
                x.compareTo(y)
            }
            "words" -> va.compareTo(vb)
            else -> vb.compareTo(va)
        }
    }

    /**
     * Who did best on [day] in [game] of [kind], once at least two played it alone with a result: their id, "t" when the best are level,
     * or null while there is nothing to compare yet.
     */
    fun winner(state: JSONObject, day: String, game: String, kind: String): String? {
        val withResult = sides(state, day, game).filter { it.hasResult }
        if (withResult.size < 2 || kind == "none") return null
        val best = withResult.sortedWith { x, y -> compare(kind, y, x) }
        return if (compare(kind, best[0], best[1]) == 0) "t" else best[0].who
    }

    /**
     * The sides [me] may see on [day] in [game]: everyone's once [me] has played it alone too (with a result, or given up on one), or
     * else the others' with their results sealed (played, not how). Sealed sides keep their time, so "who played first" still shows.
     */
    fun sealedFor(state: JSONObject, day: String, game: String, me: String?): List<SoloSide> {
        val all = sides(state, day, game)
        val open = me != null && all.any { it.who == me && it.hasResult }
        return if (open) all else all.map { if (it.who == me) it else it.copy(v = null, max = null, fail = false) }
    }

    /** Solo wins from [from] to [to] (day keys, both included): who won how many, ties not counted. */
    fun wins(state: JSONObject, kindOf: (String) -> String, from: String, to: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val solo = state.optJSONObject("solo") ?: return out
        for (day in solo.keys().asSequence().filter { DAY.matches(it) && it >= from && it <= to }) {
            for (game in games(state, day)) {
                val w = winner(state, day, game, kindOf(game)) ?: continue
                if (w != "t") out[w] = (out[w] ?: 0) + 1
            }
        }
        return out
    }

    /** A side's result in the app's own words ("3/6", "1:42", "לא נפתר"), or "" when it was only played. */
    fun fmt(kind: String, s: SoloSide): String = s.asResult()?.let { Scoring.fmtVal(kind, it) }.orEmpty()
}
