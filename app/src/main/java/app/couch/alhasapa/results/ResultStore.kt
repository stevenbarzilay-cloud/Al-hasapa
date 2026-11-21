package app.couch.alhasapa.results

import app.couch.alhasapa.catalog.Game
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.scoring.Scoring
import org.json.JSONObject

/** Writes results into the state exactly the way the hub does (same shapes, `t` on everything so histories merge). */
object ResultStore {
    sealed interface Outcome {
        /** Together game, result saved. */
        data class Saved(val points: Int) : Outcome

        /** Together game ticked without a result. */
        data object MarkedOnly : Outcome

        /** Head-to-head: this side saved; [winner] is "a", "b", "t", or null while the other side is missing. */
        data class DuelSide(val winner: String?) : Outcome

        /** Nothing to save (a head-to-head side with no result, or no side). */
        data object Nothing : Outcome
    }

    fun save(repo: StateRepository, game: Game, day: String, who: String?, input: ResultInput, now: Long = System.currentTimeMillis()): Outcome {
        val before = repo.snapshot()
        val draft = ResultDraft.evaluate(game, input.text, input.manual, input.fail, before.optJSONObject("log") ?: JSONObject(), day)
        if (game.isDuel) {
            val side = who?.takeIf { it == "a" || it == "b" } ?: return Outcome.Nothing
            val p = draft.parsed ?: return Outcome.Nothing
            var winner: String? = null
            repo.update { s ->
                val entry = CouchState.entry(s, day, game.id) ?: JSONObject().put("w", "")
                val d = entry.optJSONObject("d") ?: JSONObject()
                // Only this person's side is written; the other side stays as it was.
                d.put(side, JSONObject().put("v", p.v.let { app.couch.alhasapa.scoring.jsNumber(it) }).apply {
                    if (p.max != null) put("max", p.max)
                    put("fail", p.fail)
                    if (draft.result?.raw?.isNotEmpty() == true) put("raw", draft.result.raw)
                    put("t", now)
                })
                entry.put("d", d).put("t", now)
                CouchState.setEntry(s, day, game.id, entry)
                winner = Scoring.duelWinner(entry)
            }
            return Outcome.DuelSide(winner)
        }
        repo.update { s ->
            val cur = CouchState.entry(s, day, game.id) ?: JSONObject().put("w", "")
            draft.result?.let { cur.put("r", it.toJson()) }
            if (input.credit != null) cur.put("w", input.credit)
            cur.put("t", now)
            CouchState.setEntry(s, day, game.id, cur)
        }
        return if (draft.result != null) Outcome.Saved(draft.points) else Outcome.MarkedOnly
    }

    /** Removes the result (not the tick), or a head-to-head side. */
    fun clear(repo: StateRepository, game: Game, day: String, who: String?, now: Long = System.currentTimeMillis()) {
        repo.update { s ->
            val cur = CouchState.entry(s, day, game.id) ?: return@update
            if (game.isDuel) {
                val side = who ?: return@update
                cur.optJSONObject("d")?.remove(side)
                if (cur.optJSONObject("d")?.length() == 0) CouchState.setEntry(s, day, game.id, null) else { cur.put("t", now); CouchState.setEntry(s, day, game.id, cur) }
            } else {
                cur.remove("r"); cur.put("t", now)
                CouchState.setEntry(s, day, game.id, cur)
            }
        }
    }
}
