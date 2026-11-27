package app.couch.alhasapa.keyboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.couch.alhasapa.R
import app.couch.alhasapa.data.Doodles
import app.couch.alhasapa.data.Ink
import app.couch.alhasapa.data.Notes
import app.couch.alhasapa.ui.DoodleArt
import app.couch.alhasapa.ui.theme.Palette
import kotlin.math.roundToInt

/** The tags of the drawing's paper in a note's sheet, and of the big one in the full-screen editor, for the tests. */
const val DOODLE_TAG = "doodle"
const val DOODLE_BIG_TAG = "doodle-big"

/** One stroke of a drawing being made: its points (x, y pairs in thousandths of the paper) and its ink. */
class DoodleStroke(val points: FloatArray, val ink: Int)

/**
 * A note's drawing while it is being made (1.12): the strokes so far, and the pen in hand (a colour and a width). The small paper in
 * the note's sheet and the big one of the full-screen editor draw into the same one.
 */
@Stable
class DoodleState {
    val strokes = mutableStateListOf<DoodleStroke>()
    var color by mutableIntStateOf(Ink.OWN)
    var width by mutableIntStateOf(1)

    val ink: Int get() = Ink.of(color, width)
    val points: Int get() = strokes.sumOf { it.points.size / 2 }

    /** No room for another stroke (a note must stay small enough to send). */
    val full: Boolean get() = strokes.size >= Notes.MAX_STROKES || points >= Notes.MAX_POINTS

    fun add(points: FloatArray, ink: Int) {
        if (points.size < 2 || full) return
        val room = (Notes.MAX_POINTS - this.points).coerceAtLeast(1)
        strokes += DoodleStroke(if (points.size / 2 > room) points.copyOf(room * 2) else points, ink)
    }

    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }
    fun clear() = strokes.clear()

    /** The drawing as a note keeps it: whole thousandths, and each stroke's ink. */
    fun doodle(): List<IntArray> = strokes.map { s -> IntArray(s.points.size) { s.points[it].roundToInt().coerceIn(0, 1000) } }
    fun inks(): List<Int> = strokes.map { it.ink }
}

/**
 * The paper: a finger draws on it. Every point the screen reports is taken (the ones between two frames too), from the very first
 * touch, so a dot is a dot and a small letter keeps its shape; the line is drawn smooth as it grows; when the finger lifts, the points
 * that add nothing are left out ([Doodles.simplify]) and the stroke is kept. One finger at a time.
 */
@Composable
internal fun DoodlePad(state: DoodleState, own: Color, modifier: Modifier = Modifier, tag: String = DOODLE_TAG) {
    // The stroke under the finger, in this paper's own pixels.
    val live = remember { mutableStateListOf<Offset>() }
    var liveInk by remember { mutableIntStateOf(Ink.DEFAULT) }
    val description = stringResource(R.string.note_doodle)
    Canvas(
        modifier
            .aspectRatio(Doodles.ASPECT)
            .clip(RoundedCornerShape(14.dp))
            .background(DoodleArt.PAPER)
            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(14.dp))
            .testTag(tag)
            .semantics { contentDescription = description }
            .pointerInput(state) {
                val near = 1.dp.toPx()
                val eps = 0.6.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (state.full) return@awaitEachGesture
                    live.clear(); liveInk = state.ink
                    live += down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        for (past in ch.historical) if ((past.position - live.last()).getDistance() >= near) live += past.position
                        if ((ch.position - live.last()).getDistance() >= near) live += ch.position
                        if (!ch.pressed) break
                        ch.consume()
                    }
                    val w = size.width.toFloat().coerceAtLeast(1f); val h = size.height.toFloat().coerceAtLeast(1f)
                    val kept = Doodles.simplify(FloatArray(live.size * 2) { if (it % 2 == 0) live[it / 2].x else live[it / 2].y }, eps)
                    state.add(FloatArray(kept.size) { ((if (it % 2 == 0) kept[it] / w else kept[it] / h) * 1000f).coerceIn(0f, 1000f) }, liveInk)
                    live.clear()
                }
            },
    ) {
        val w = size.width; val h = size.height
        with(DoodleArt) {
            for (s in state.strokes) {
                stroke(s.points.size / 2, { s.points[2 * it] / 1000f * w }, { s.points[2 * it + 1] / 1000f * h }, color(s.ink, own), Ink.units(s.ink) / 1000f * w)
            }
            if (live.isNotEmpty()) stroke(live.size, { live[it].x }, { live[it].y }, color(liveInk, own), Ink.units(liveInk) / 1000f * w)
        }
    }
}

/**
 * The drawing at the size of the whole screen (1.12): the paper as large as it fits, and beside it (under it on a phone held upright)
 * the pens: the colours (the writer's own first), three widths, the eraser, "undo" for the last stroke and "clear".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DoodleEditor(state: DoodleState, own: Color, onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Cream).safeDrawingPadding().padding(10.dp)) {
            val wide = maxWidth > maxHeight
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.width(214.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tools(state, own)
                        Done(onDone)
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        // As large as fits beside the pens: as tall as the screen allows, or as wide.
                        val w = if (maxWidth < maxHeight * Doodles.ASPECT) maxWidth else maxHeight * Doodles.ASPECT
                        DoodlePad(state, own, Modifier.width(w), tag = DOODLE_BIG_TAG)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.doodle_title), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    DoodlePad(state, own, Modifier.fillMaxWidth(), tag = DOODLE_BIG_TAG)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Tools(state, own) }
                    Done(onDone)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tools(state: DoodleState, own: Color) {
    val names = listOf(
        R.string.doodle_color_own, R.string.doodle_color_ink, R.string.doodle_color_red, R.string.doodle_color_orange, R.string.doodle_color_mustard,
        R.string.doodle_color_green, R.string.doodle_color_teal, R.string.doodle_color_blue, R.string.doodle_color_purple, R.string.doodle_color_pink,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in names.indices) Swatch(DoodleArt.color(Ink.of(c, 1), own), stringResource(names[c]), state.color == c) { state.color = c }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val widths = listOf(R.string.doodle_width_thin to 6.dp, R.string.doodle_width_medium to 11.dp, R.string.doodle_width_thick to 19.dp)
        widths.forEachIndexed { i, (name, dot) ->
            WidthDot(dot, stringResource(name), state.width == i, if (state.color == Ink.PAPER) Palette.Ink2 else DoodleArt.color(state.ink, own)) { state.width = i }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Tool(stringResource(R.string.doodle_eraser), chosen = state.color == Ink.PAPER) { state.color = if (state.color == Ink.PAPER) Ink.OWN else Ink.PAPER }
        Tool(stringResource(R.string.doodle_undo), enabled = state.strokes.isNotEmpty()) { state.undo() }
        Tool(stringResource(R.string.note_clear), enabled = state.strokes.isNotEmpty()) { state.clear() }
    }
    if (state.full) Text(stringResource(R.string.doodle_full), fontSize = 15.sp, color = Palette.Ink2)
}

@Composable
private fun Swatch(color: Color, name: String, chosen: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (chosen) Palette.Ink else Color.Transparent)
            .clickable(onClick = onClick).semantics { role = Role.RadioButton; selected = chosen; contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(if (chosen) 32.dp else 36.dp).clip(CircleShape).background(color).border(BorderStroke(1.5.dp, if (chosen) Palette.Cream else Palette.Line2), CircleShape))
    }
}

@Composable
private fun WidthDot(dot: Dp, name: String, chosen: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (chosen) Palette.Tint else Color.Transparent)
            .border(BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) Palette.Ink else Palette.Line2), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).semantics { role = Role.RadioButton; selected = chosen; contentDescription = name },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(dot).clip(CircleShape).background(color)) }
}

@Composable
private fun Tool(label: String, chosen: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        color = if (!enabled) Palette.Ink2.copy(alpha = 0.5f) else if (chosen) Palette.Cream else Palette.Ink,
        modifier = Modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(14.dp)).background(if (chosen) Palette.Ink else Palette.Tint)
            .clickable(enabled = enabled, onClick = onClick).semantics { role = Role.Button }.padding(horizontal = 14.dp, vertical = 11.dp),
    )
}

@Composable
private fun Done(onDone: () -> Unit) {
    Text(
        stringResource(R.string.doodle_done), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.Cream, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Ink)
            .clickable(onClick = onDone).semantics { role = Role.Button }.padding(vertical = 13.dp),
    )
}
