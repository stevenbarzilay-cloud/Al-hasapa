package app.couch.alhasapa.player

import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.PieceStyle
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.scoring.Scoring
import org.json.JSONObject

/** What the side panels show: tonight's progress and points, and the two people. Built from the state each time it changes. */
data class PlayerInfo(
    val done: Int,
    val total: Int,
    val points: Int,
    val nameA: String,
    val nameB: String,
    val pieceA: PieceStyle,
    val pieceB: PieceStyle,
    /** "a" or "b" when a head-to-head game is being played by one of them. */
    val who: String?,
    /** The others here this evening (1.10), for "who cracked it". */
    val others: List<app.couch.alhasapa.results.CreditOther> = emptyList(),
) {
    companion object {
        fun from(state: JSONObject, catalog: Catalog, pieces: PiecesConfig, day: String, who: String?, evening: String = app.couch.alhasapa.data.Couple.evening()): PlayerInfo {
            val lib = catalog.games.filter { catalog.inLib(it, state) }
            val (a, b) = CouchState.names(state)
            return PlayerInfo(
                done = lib.count { CouchState.entry(state, day, it.id) != null },
                total = lib.size,
                points = Scoring.dayPts(state.optJSONObject("log") ?: JSONObject(), day),
                nameA = a, nameB = b,
                pieceA = pieces.styleFor(state, "a"), pieceB = pieces.styleFor(state, "b"),
                who = who,
                others = app.couch.alhasapa.data.Couple.everyone(state, evening).filter { it.other }
                    .map { app.couch.alhasapa.results.CreditOther(it.who, it.name, pieces.styleOf(it)) },
            )
        }
    }
}
