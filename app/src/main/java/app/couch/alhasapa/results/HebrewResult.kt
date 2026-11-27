package app.couch.alhasapa.results

import app.couch.alhasapa.scoring.Scoring

/**
 * A game's end screen in words (1.9.10), for the games that hand nothing over to copy: Haaretz's say how many tries it took, the
 * mistakes or the hints, sometimes a time or a score out of something, and how we did against everyone who played. When "save the
 * result" is pressed and nothing was copied, the screen's short lines that speak of a result are read ([ResultWatch.LINES]) and turned
 * into numbers here; the result sheet opens with those numbers in our own words ([summary]), to be checked and saved.
 *
 * Only the numbers are kept, never a line of the page: on a game that was lost, a line may hold the answer.
 *
 * The wording of the end screens was not seen when this was written (the sites could not be opened from where it was built): the
 * patterns are the common Hebrew ways of saying these things, and the sheet always shows what was read, to be mended with a press.
 */
object HebrewResult {
    data class Reading(
        /** Tries (guesses, attempts) it took, and out of how many when the screen says. */
        val tries: Int? = null,
        val outOf: Int? = null,
        val mistakes: Int? = null,
        val hints: Int? = null,
        val seconds: Int? = null,
        /** A score out of something ("17 מתוך 20", "8/10"). */
        val score: Double? = null,
        val scoreMax: Double? = null,
        /** How many of the other players we did better than, in percent (0 to 100). */
        val beat: Int? = null,
        /** The screen says it was not solved. */
        val failed: Boolean = false,
    ) {
        val any: Boolean get() = tries != null || mistakes != null || hints != null || seconds != null || score != null || beat != null || failed
    }

    private const val DASH = "[-־–]?"
    private val ORDINALS = mapOf("ראשון" to 1, "ראשונה" to 1, "שני" to 2, "שנייה" to 2, "שניה" to 2, "שלישי" to 3, "שלישית" to 3,
        "רביעי" to 4, "רביעית" to 4, "חמישי" to 5, "חמישית" to 5, "שישי" to 6, "שישית" to 6, "שביעי" to 7, "שמיני" to 8)

    private val TRIES = Regex("(?:ב$DASH)?([0-9]{1,2})\\s*(?:ניסיונות|נסיונות|ניחושים|ניסיונים)")
    private val TRY_NTH = Regex("ב(?:ניסיון|ניחוש)\\s*ה?$DASH([0-9]{1,2})(?![0-9])")
    private val TRY_ORD = Regex("ב(?:ניסיון|ניחוש)\\s*ה(ראשון|ראשונה|שני|שנייה|שניה|שלישי|שלישית|רביעי|רביעית|חמישי|חמישית|שישי|שישית|שביעי|שמיני)")
    private val TRY_ONE = Regex("(?:ב)?(?:ניסיון|ניחוש) אחד")
    /** "Out of 6", on a line that speaks of tries. */
    private val OUT_OF = Regex("מתוך\\s*([0-9]{1,2})(?![0-9])")
    private val TRY_WORDS = Regex("ניסיון|ניסיונות|נסיונות|ניחוש")
    private val MISTAKES = Regex("([0-9]{1,2})\\s*טעויות")
    private val MISTAKE_ONE = Regex("טעות אחת")
    private val MISTAKES_NONE = Regex("(?:בלי|ללא|אפס|0)\\s*טעויות|(?:בלי|ללא)\\s*טעות")
    private val HINTS = Regex("([0-9]{1,2})\\s*רמזים")
    private val HINT_ONE = Regex("רמז אחד")
    private val HINTS_NONE = Regex("(?:בלי|ללא|אפס|0)\\s*רמזים|(?:בלי|ללא)\\s*רמז")
    private val TIME = Regex("(?<![0-9:])([0-9]{1,2}):([0-9]{2})(?![0-9:])")
    private val SCORE = Regex("(?<![0-9])([0-9]{1,3})\\s*(?:/|מתוך)\\s*([0-9]{1,3})(?![0-9])")
    private val PERCENT = Regex("([0-9]{1,3}(?:\\.[0-9]+)?)\\s*%")
    /** Words that make a percentage ours against the others ("better than 82% of the players", "faster than ..."). */
    private val BETTER = Regex("טוב|טובים|טובה|מהר|מהירים|מהירה|יותר|עקפ|ניצח|הקדמ|לפני")
    private val OTHERS = Regex("מה?(?:שחקנים|משתתפים|פותרים|גולשים|משחקים|קוראים|מנויים)")
    /** "Among the top 10%": the best of them. */
    private val TOP = Regex("(?:בין|ב)\\s*(?:ה)?$DASH([0-9]{1,3})\\s*%\\s*(?:ה)?(?:טובים|מהירים|ראשונים|מובילים|מצטיינים)")
    private val FAILED = Regex("לא הצלחת|לא פתרת|לא הצלחנו|לא פתרנו|נגמרו (?:לכם |לך )?(?:הניסיונות|הניחושים)|הפעם לא")

    /** Reads the lines of an end screen; nothing is kept of them but the numbers. */
    fun read(text: String?): Reading {
        if (text.isNullOrBlank()) return Reading()
        var r = Reading()
        for (raw in text.split('\n')) {
            val line = raw.replace(Regex("\\s+"), " ").trim()
            if (line.isEmpty() || line.length > 160) continue
            if (r.tries == null) {
                r = TRY_NTH.find(line)?.let { r.copy(tries = it.groupValues[1].toInt()) }
                    ?: TRY_ORD.find(line)?.let { r.copy(tries = ORDINALS[it.groupValues[1]]) }
                    ?: TRIES.find(line)?.let { r.copy(tries = it.groupValues[1].toInt()) }
                    ?: (if (TRY_ONE.containsMatchIn(line)) r.copy(tries = 1) else r)
            }
            if (r.outOf == null && TRY_WORDS.containsMatchIn(line)) OUT_OF.find(line)?.let { r = r.copy(outOf = it.groupValues[1].toInt()) }
            if (r.mistakes == null) {
                r = when {
                    MISTAKES_NONE.containsMatchIn(line) -> r.copy(mistakes = 0)
                    MISTAKE_ONE.containsMatchIn(line) -> r.copy(mistakes = 1)
                    else -> MISTAKES.find(line)?.let { r.copy(mistakes = it.groupValues[1].toInt()) } ?: r
                }
            }
            if (r.hints == null) {
                r = when {
                    HINTS_NONE.containsMatchIn(line) -> r.copy(hints = 0)
                    HINT_ONE.containsMatchIn(line) -> r.copy(hints = 1)
                    else -> HINTS.find(line)?.let { r.copy(hints = it.groupValues[1].toInt()) } ?: r
                }
            }
            if (r.seconds == null) TIME.find(line)?.let { m ->
                val min = m.groupValues[1].toInt(); val sec = m.groupValues[2].toInt()
                if (sec < 60) r = r.copy(seconds = min * 60 + sec)
            }
            if (r.beat == null) {
                TOP.find(line)?.let { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 1..100 }?.let { r = r.copy(beat = 100 - it) } }
            }
            if (r.beat == null && OTHERS.containsMatchIn(line) && BETTER.containsMatchIn(line)) {
                PERCENT.find(line)?.let { m -> m.groupValues[1].toDoubleOrNull()?.takeIf { it in 0.0..100.0 }?.let { r = r.copy(beat = Math.round(it).toInt()) } }
            }
            if (r.score == null && !line.contains('%') && !TIME.containsMatchIn(line)) SCORE.find(line)?.let { m ->
                val a = m.groupValues[1].toDouble(); val b = m.groupValues[2].toDouble()
                // A score out of its whole, not the tries out of the tries allowed.
                if (b > 0 && a <= b && !TRIES.containsMatchIn(line) && !line.contains("ניסיון")) r = r.copy(score = a, scoreMax = b)
            }
            if (!r.failed && FAILED.containsMatchIn(line)) r = r.copy(failed = true)
        }
        return r
    }

    /**
     * What was read, in our own words, as the result sheet's text: first in the words of the game's own kind (so the sheet reads it
     * the way it reads anything copied: "3/6 ניסיונות", "2 טעויות", "4:05"), then how we did against the others. Empty when nothing
     * fits. [tries] is how many tries the game allows when the screen does not say (six for a Wordle).
     */
    fun summary(r: Reading, kind: String, tries: Int?): String {
        val parts = ArrayList<String>()
        when (kind) {
            "guesses" -> {
                val max = r.outOf ?: tries ?: 6
                if (r.failed) parts += "X/$max ניסיונות" else r.tries?.let { parts += "$it/$max ניסיונות" }
            }
            "mistakes" -> r.mistakes?.let { parts += "$it טעויות" }
            "hints" -> r.hints?.let { parts += "$it רמזים" }
            "time" -> r.seconds?.let { parts += Scoring.fmtT(it.toDouble()) }
            "score" -> r.score?.let { s -> r.scoreMax?.let { m -> parts += "${num(s)}/${num(m)}" } }
        }
        // Whatever else the screen said, for the eye (the sheet reads only its kind's words, and the standing when nothing else fits).
        if (kind != "guesses") r.tries?.let { parts += "$it ניסיונות" }
        if (kind != "mistakes") r.mistakes?.let { parts += "$it טעויות" }
        r.beat?.let { parts += standing(it) }
        if (r.failed && kind != "guesses") parts += "לא הצלחנו הפעם"
        return parts.joinToString(" · ")
    }

    /** How we did against the others, in the words [beatIn] reads back. */
    fun standing(beat: Int): String = "טובים מ-$beat% מהשחקנים"

    private val STANDING = Regex("טובים מ-([0-9]{1,3})% מהשחקנים")

    /** The standing that [standing] wrote, or null. */
    fun beatIn(text: String?): Int? = text?.let { STANDING.find(it) }?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }

    private fun num(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}
