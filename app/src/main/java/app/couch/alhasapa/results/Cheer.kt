package app.couch.alhasapa.results

import app.couch.alhasapa.R

/**
 * The little celebration when a result is saved on the TV (1.9.7): the couch points it is worth, a word about them, and the evening so
 * far. A word for each kind of evening: a perfect one, a fine one, a so-so one, and one that did not work out this time.
 */
data class Cheer(val points: Int, val line: Int, val evening: Int)

object Cheers {
    /**
     * For a saved result worth [points] in all (2 for playing and up to 10 for how it went), of [kind]; [newBest] when a time beat the
     * best time we had for the game before. Null for nothing to celebrate (a head-to-head side: its own scoreboard says who won).
     */
    fun of(outcome: ResultStore.Outcome, kind: String, newBest: Boolean, evening: Int): Cheer? = when (outcome) {
        is ResultStore.Outcome.Saved -> Cheer(outcome.points, line(outcome.points - 2, kind, newBest), evening)
        ResultStore.Outcome.MarkedOnly -> Cheer(2, R.string.cheer_marked, evening)
        else -> null
    }

    fun line(resultPoints: Int, kind: String, newBest: Boolean): Int = when {
        resultPoints <= 0 -> R.string.cheer_tomorrow
        resultPoints >= 10 && kind == "time" && newBest -> R.string.cheer_best
        resultPoints >= 10 -> R.string.cheer_bullseye
        resultPoints >= 7 -> R.string.cheer_great
        resultPoints >= 4 -> R.string.cheer_good
        else -> R.string.cheer_fun
    }
}
