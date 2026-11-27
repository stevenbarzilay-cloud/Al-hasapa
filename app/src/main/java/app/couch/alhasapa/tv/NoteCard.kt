package app.couch.alhasapa.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.aspectRatio
import app.couch.alhasapa.data.Doodles
import app.couch.alhasapa.ui.DoodleArt
import app.couch.alhasapa.R
import app.couch.alhasapa.data.Note
import app.couch.alhasapa.data.PieceStyle
import app.couch.alhasapa.ui.PieceIcon
import app.couch.alhasapa.ui.theme.Palette

/** A note as the TV shows it: with the names of who wrote it and for whom, the writer's piece, and the game's name. */
data class NoteView(val note: Note, val fromName: String, val toName: String, val piece: PieceStyle, val gameName: String?) {
    companion object {
        fun of(n: Note, state: org.json.JSONObject, pieces: app.couch.alhasapa.data.PiecesConfig, catalog: app.couch.alhasapa.catalog.Catalog): NoteView {
            val from = app.couch.alhasapa.data.Couple.person(state, n.from)
            val to = n.to ?: if (n.from == "a") "b" else "a"
            return NoteView(
                n, app.couch.alhasapa.data.Couple.name(state, n.from), app.couch.alhasapa.data.Couple.name(state, to),
                pieces.styleOf(from), n.game?.let { catalog.game(it)?.n },
            )
        }
    }
}

/** The note card's own buttons on a TV: while it is up, the arrows stay on them. */
const val NOTE_PREFIX = "note:"
const val NOTE_OK_ID = NOTE_PREFIX + "ok"

/**
 * A note one of us left the other (1.9.9), on the TV: a card a little askew, like a paper note, with who it is from, the words, and the
 * drawing in the writer's colour. One OK (or Back) and it is put away, and not shown again.
 */
@Composable
fun NoteCard(view: NoteView, onDone: () -> Unit) {
    val note = view.note
    val piece = view.piece
    val tv = LocalTv.current
    val done by rememberUpdatedState(onDone)
    if (tv != null) DisposableEffect(tv, note.id) {
        tv.modalPrefix = NOTE_PREFIX
        tv.focus(NOTE_OK_ID)
        onDispose { tv.endModal() }
    }
    BackHandler { done() }
    // A drawing made since 1.12 has its own paper (three to two), shown whole and large: beside the words when there are words, or
    // alone under the names. One from before is a strip under the words, as it was.
    val paper = note.doodle.isNotEmpty() && note.ink != null
    val beside = paper && note.text.isNotEmpty()
    Box(Modifier.fillMaxSize().background(Palette.Ink.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = if (beside) 860.dp else 620.dp).padding(24.dp).rotate(-1.5f).clip(RoundedCornerShape(18.dp)).background(DoodleArt.PAPER)
                .border(BorderStroke(2.dp, Palette.Ink), RoundedCornerShape(18.dp)).padding(horizontal = 26.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val words: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PieceIcon(piece, 34.dp, label = "")
                    Text(stringResource(R.string.note_from, view.fromName, view.toName), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                }
                if (view.gameName != null) Text(stringResource(R.string.note_after_game, view.gameName), fontSize = 16.sp, color = Palette.Ink2)
                if (note.text.isNotEmpty()) Text(note.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, lineHeight = 36.sp)
            }
            if (beside) {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { words() }
                    Doodle(note.doodle, piece.accent, Modifier.width(390.dp).aspectRatio(Doodles.ASPECT), note.ink)
                }
            } else {
                words()
                if (paper) Doodle(note.doodle, piece.accent, Modifier.align(Alignment.CenterHorizontally).width(420.dp).aspectRatio(Doodles.ASPECT), note.ink)
                else if (note.doodle.isNotEmpty()) Doodle(note.doodle, piece.accent, Modifier.fillMaxWidth().height(200.dp))
            }
            val ok = stringResource(R.string.note_ok)
            Box(
                Modifier.align(Alignment.End).height(52.dp).widthIn(min = 160.dp).clip(RoundedCornerShape(26.dp)).background(Palette.Ink)
                    .tvTarget(NOTE_OK_ID, ok) { done() }.semantics { role = Role.Button; contentDescription = ok },
                contentAlignment = Alignment.Center,
            ) { Text(ok, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Palette.Cream, modifier = Modifier.padding(horizontal = 20.dp), textAlign = TextAlign.Center) }
        }
    }
}

/**
 * The drawing: each stroke through its points (thousandths of the box), round at the ends. With [ink] (1.12) each stroke in its own
 * colour and width ([color] is the writer's own); without, all of them in [color], as a note from before was drawn.
 */
@Composable
fun Doodle(strokes: List<IntArray>, color: Color, modifier: Modifier, ink: List<Int>? = null) {
    Canvas(modifier) { with(DoodleArt) { doodle(strokes, ink, color) } }
}
