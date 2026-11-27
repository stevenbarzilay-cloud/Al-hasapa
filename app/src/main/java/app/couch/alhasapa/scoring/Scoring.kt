package app.couch.alhasapa.scoring

import org.json.JSONObject

/** A parsed or entered result: the prototype's `{v, max?, fail, kind, raw?}`. */
data class Result(
    val v: Double,
    val max: Int? = null,
    val fail: Boolean = false,
    val kind: String = "",
    val raw: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("v", jsNumber(v)).apply {
        if (max != null) put("max", max)
        put("fail", fail)
        put("kind", kind)
        if (!raw.isNullOrEmpty()) put("raw", raw)
    }

    companion object {
        /** Null when there is no usable value, exactly where the prototype's `resultPts` returns 0 for it. */
        fun fromJson(o: JSONObject?): Result? {
            if (o == null || !o.has("v") || o.isNull("v")) return null
            val raw = o.get("v")
            if (raw is String && raw.isEmpty()) return null
            val v = (raw as? Number)?.toDouble() ?: return null
            return Result(
                v = v,
                max = if (o.has("max") && !o.isNull("max")) o.optInt("max") else null,
                fail = o.optBoolean("fail", false),
                kind = o.optString("kind", ""),
                raw = if (o.has("raw")) o.optString("raw") else null,
            )
        }
    }
}

/** What parsing a share text yields, before it is attached to a game's kind. */
data class Parsed(val v: Double, val max: Int?, val fail: Boolean)

/** A number printed the way JavaScript prints it (3, not 3.0). */
fun jsNumber(d: Double): Any = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong() else d

/**
 * Team scoring and result parsing, ported EXACTLY from the prototype (couch-artifact/index.html: parseResult, resultPts,
 * entryPts, duelWinner, fmtT, fmtVal). ScoringGoldenTest replays hundreds of cases recorded from the original functions.
 *
 * The regexes spell out JavaScript's `\s`, `\d` and `\b` explicitly: Android's regex engine and JS disagree about them for
 * Hebrew text, and the whole point is to behave the same.
 */
object Scoring {
    /** JavaScript's whitespace set (`\s`, `String.trim`). */
    private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val S = "[$WS]*"
    private val WS_ONE = Regex("[$WS]")

    /** JavaScript's `\b` where the next/previous character is a word character (ASCII only). */
    private const val NOT_AFTER_WORD = "(?<![A-Za-z0-9_])"
    private const val NOT_BEFORE_WORD = "(?![A-Za-z0-9_])"

    private val TIME_HMS = Regex("([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?")
    private val TIME_SEC = Regex("([0-9]+)${S}(?:seconds|sec|שניות)", RegexOption.IGNORE_CASE)
    private val GUESS_FRACTION = Regex("$NOT_AFTER_WORD([1-9X])$S/$S([1-9][0-9]?)$NOT_BEFORE_WORD", RegexOption.IGNORE_CASE)
    private val GUESS_WORD = Regex("([0-9]+)${S}(?:guess(?:es)?|ניחושים|ניסיונות|clues?)", RegexOption.IGNORE_CASE)
    private val MISTAKE_WORD = Regex("([0-9])${S}(?:mistakes?|טעויות|טעות)", RegexOption.IGNORE_CASE)
    /** Hints, or the steps a game counts them in (Haaretz's "מי אני?": "הצלחת לזהות את הדמות תוך 6 צעדים", 1.11.2). */
    private val HINT_WORD = Regex("([0-9]+)${S}(?:hints?|רמזים|צעדים|steps?)", RegexOption.IGNORE_CASE)
    private val HINT_ONE = Regex("(?:רמז|צעד) אחד")
    private val WORDS_WORD = Regex("([0-9]+)${S}(?:words?|מילים)", RegexOption.IGNORE_CASE)

    /** A score out of something ("8/10", "41.23/50", "34,567/50,000"): the first fraction in the text. */
    private val SCORE_FRACTION = Regex("$NOT_AFTER_WORD([0-9][0-9,]*(?:\\.[0-9]+)?)$S/$S([0-9][0-9,]*(?:\\.[0-9]+)?)")
    /** A score with no "out of" ("12 points", "9 correct"). */
    private val SCORE_WORD = Regex("([0-9][0-9,]*(?:\\.[0-9]+)?)${S}(?:points?|pts|נקודות|נק׳|correct|נכונות|נכונים)", RegexOption.IGNORE_CASE)

    private fun number(s: String): Double? = s.replace(",", "").toDoubleOrNull()

    private const val LIGHT_BULB = 0x1F4A1
    private val SQUARES = 0x1F7E6..0x1F7EB

    val KIND_LABEL = mapOf(
        "guesses" to "ניסיונות", "mistakes" to "טעויות", "hints" to "רמזים",
        "time" to "זמן", "words" to "מילים", "score" to "נקודות", "none" to "תוצאה",
    )

    private fun jsTrim(s: String): String = s.trim { WS_ONE.matches(it.toString()) }

    /** Reads a share text (or typed number) of the given kind; null if nothing recognisable is in it. */
    fun parseResult(text: String?, kind: String): Parsed? {
        val t = jsTrim(text ?: "")
        if (t.isEmpty()) return null
        when (kind) {
            "time" -> {
                TIME_HMS.find(t)?.let { m ->
                    val v = if (m.groups[3] != null) {
                        m.groupValues[1].toDouble() * 3600 + m.groupValues[2].toDouble() * 60 + m.groupValues[3].toDouble()
                    } else {
                        m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble()
                    }
                    return Parsed(v, null, false)
                }
                TIME_SEC.find(t)?.let { return Parsed(it.groupValues[1].toDouble(), null, false) }
                return null
            }
            "guesses" -> {
                GUESS_FRACTION.find(t)?.let { m ->
                    val max = m.groupValues[2].toInt()
                    return if (m.groupValues[1].uppercase() == "X") Parsed((max + 1).toDouble(), max, true)
                    else Parsed(m.groupValues[1].toDouble(), max, false)
                }
                GUESS_WORD.find(t)?.let { return Parsed(it.groupValues[1].toDouble(), 5, false) }
                return null
            }
            "mistakes" -> {
                val rows = t.split("\n").map { line ->
                    line.replace(WS_ONE, "").codePoints().toArray().filter { it in SQUARES }
                }.filter { it.size == 4 }
                if (rows.isNotEmpty()) {
                    val bad = rows.count { it.toSet().size > 1 }
                    val good = rows.size - bad
                    return Parsed(bad.toDouble(), null, good < 4 && bad >= 4)
                }
                MISTAKE_WORD.find(t)?.let { m ->
                    val n = m.groupValues[1].toInt()
                    return Parsed(n.toDouble(), null, n >= 4)
                }
                return null
            }
            "hints" -> {
                val bulbs = t.codePoints().toArray().count { it == LIGHT_BULB }
                if (t.contains("🔵") || t.contains("🟡") || bulbs > 0) return Parsed(bulbs.toDouble(), null, false)
                HINT_WORD.find(t)?.let { return Parsed(it.groupValues[1].toDouble(), null, false) }
                if (HINT_ONE.containsMatchIn(t)) return Parsed(1.0, null, false)
                return null
            }
            "words" -> {
                WORDS_WORD.find(t)?.let { return Parsed(it.groupValues[1].toDouble(), null, false) }
                return null
            }
            // Not in the prototype (1.9.7): games where more is better, a quiz's "8/10" or a score's "41.23/50".
            "score" -> {
                SCORE_FRACTION.findAll(t).forEach { m ->
                    val a = number(m.groupValues[1]); val b = number(m.groupValues[2])
                    if (a != null && b != null && b > 0 && a <= b) return Parsed(a, b.toInt().takeIf { it.toDouble() == b } ?: kotlin.math.ceil(b).toInt(), false)
                }
                SCORE_WORD.find(t)?.let { m -> number(m.groupValues[1])?.let { return Parsed(it, null, false) } }
                return null
            }
        }
        return null
    }

    /** The best score for this game on any OTHER day, among scores with nothing to be out of (failed ones do not count). */
    fun bestScore(log: JSONObject, id: String, excludeDay: String?): Double? {
        var best: Double? = null
        for (day in log.keys()) {
            if (day == excludeDay) continue
            val r = log.optJSONObject(day)?.optJSONObject(id)?.optJSONObject("r") ?: continue
            if (r.optString("kind") == "score" && !r.optBoolean("fail", false) && !r.has("max") && r.has("v") && !r.isNull("v")) {
                val v = r.optDouble("v")
                best = if (best == null) v else maxOf(best, v)
            }
        }
        return best
    }

    /** A personal best time for this game on any OTHER day (failed runs do not count). */
    fun bestTime(log: JSONObject, id: String, excludeDay: String?): Double? {
        var best: Double? = null
        for (day in log.keys()) {
            if (day == excludeDay) continue
            val r = log.optJSONObject(day)?.optJSONObject(id)?.optJSONObject("r") ?: continue
            if (r.optString("kind") == "time" && !r.optBoolean("fail", false) && r.has("v") && !r.isNull("v")) {
                val v = r.optDouble("v")
                best = if (best == null) v else minOf(best, v)
            }
        }
        return best
    }

    /** Up to 10 points for how well they played (JS Math.round semantics: halves round up). */
    fun resultPts(log: JSONObject, id: String, r: Result?, day: String?): Int {
        if (r == null || r.fail) return 0
        return when (r.kind) {
            "guesses" -> {
                val m = (r.max ?: 0).let { if (it == 0) 6 else it }
                maxOf(1, jsRound(10.0 * (m + 1 - r.v) / m))
            }
            "mistakes" -> tableAt(intArrayOf(10, 7, 4, 2), r.v)
            "hints" -> tableAt(intArrayOf(10, 7, 5, 3), r.v)
            "time" -> {
                val b = bestTime(log, id, day)
                if (b == null || r.v < b) 10 else if (r.v <= b * 1.25) 7 else 5
            }
            "words" -> minOf(10, maxOf(2, jsRound(14 - r.v * 2)))
            // More is better: out of its whole, up to 10 (at least 1 for playing it through); with no whole, against our best.
            "score" -> {
                val m = r.max
                if (m != null && m > 0) minOf(10, maxOf(1, jsRound(10.0 * r.v / m)))
                else { val b = bestScore(log, id, day); if (b == null || r.v >= b) 10 else if (r.v >= b * 0.8) 7 else 5 }
            }
            else -> 5
        }
    }

    private fun tableAt(table: IntArray, v: Double): Int {
        val i = minOf(v, 3.0)
        // The prototype would index with a fraction or a negative number and get `undefined`; nothing produces those.
        return if (i >= 0 && i == Math.floor(i)) table[i.toInt()] else 0
    }

    private fun jsRound(x: Double): Int = Math.floor(x + 0.5).toInt()

    /** 2 points for playing (nothing for a head-to-head entry, which has its own scoreboard) plus the result's points. */
    fun entryPts(log: JSONObject, id: String, entry: JSONObject?, day: String?): Int {
        if (entry == null || (entry.has("d") && !entry.isNull("d"))) return 0
        return 2 + resultPts(log, id, Result.fromJson(entry.optJSONObject("r")), day)
    }

    fun dayPts(log: JSONObject, day: String): Int {
        val d = log.optJSONObject(day) ?: return 0
        var sum = 0
        for (id in d.keys()) sum += entryPts(log, id, d.optJSONObject(id), day)
        return sum
    }

    /** "a", "b" or "t" (tie); null until both sides have a result. Lower wins: time, or guesses in Pinpoint; a miss loses. */
    fun duelWinner(entry: JSONObject?): String? {
        val d = entry?.optJSONObject("d") ?: return null
        val a = d.optJSONObject("a") ?: return null
        val b = d.optJSONObject("b") ?: return null
        fun score(x: JSONObject) = if (x.optBoolean("fail", false)) Double.POSITIVE_INFINITY else x.optDouble("v", Double.NaN)
        val sa = score(a)
        val sb = score(b)
        return if (sa == sb) "t" else if (sa < sb) "a" else "b"
    }

    fun fmtT(seconds: Double): String {
        val s = Math.floor(seconds + 0.5).toLong()
        val h = s / 3600
        val m = s % 3600 / 60
        val x = s % 60
        return (if (h != 0L) "$h:${m.toString().padStart(2, '0')}" else m.toString()) + ":" + x.toString().padStart(2, '0')
    }

    fun fmtVal(kind: String, r: Result?): String = when {
        r == null -> ""
        r.fail -> "לא נפתר"
        kind == "time" -> fmtT(r.v)
        kind == "guesses" -> jsNumber(r.v).toString() + (if ((r.max ?: 0) != 0) "/${r.max}" else "")
        kind == "score" && (r.max ?: 0) != 0 -> jsNumber(r.v).toString() + "/${r.max}"
        else -> jsNumber(r.v).toString() + " " + KIND_LABEL[kind]
    }
}
