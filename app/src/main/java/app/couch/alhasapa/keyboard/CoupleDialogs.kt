package app.couch.alhasapa.keyboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.couch.alhasapa.R
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.data.Notes
import app.couch.alhasapa.data.Person
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.ui.PieceIcon
import app.couch.alhasapa.ui.theme.Palette
import kotlin.math.roundToInt

/** The drawing pad of a note, for the tests. */
const val DOODLE_TAG = "doodle"

/**
 * Who holds this phone, and how they look everywhere (1.9.9): one of us, their name, their shape and their colour. Saved here and sent
 * to the TV, which hands it on to the other phones. From 1.10 also the others: someone who comes often ("+ משתמש", kept until removed)
 * or a guest ("+ אורח", for this evening only); their colour marks them and nothing else.
 */
@Composable
internal fun IdentityDialog(
    people: List<Person>, me: String?, pieces: PiecesConfig, onSave: (Person) -> Unit, onDismiss: () -> Unit,
    onRemove: (String) -> Unit = {}, today: String = "",
) {
    var who by remember { mutableStateOf(me?.takeIf { w -> people.any { it.who == w } } ?: "") }
    // A new one being added: its id, and whether it is a guest.
    var draft by remember { mutableStateOf<Person?>(null) }
    val current = draft?.takeIf { it.who == who } ?: people.firstOrNull { it.who == who }
    var name by remember(who) { mutableStateOf(current?.name.orEmpty()) }
    var shape by remember(who) { mutableStateOf(current?.let { pieces.styleOf(it).shapeId }.orEmpty()) }
    var color by remember(who) { mutableStateOf(current?.let { pieces.styleOf(it).colorId }.orEmpty()) }
    fun add(guest: Boolean) {
        val p = Person(Couple.newId(), "", "circle", if (guest) "mustard" else "teal", 0L, guest = guest, day = if (guest) today else null)
        draft = p; who = p.who
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(12.dp).widthIn(max = 820.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Cream)
                .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          // What is chosen scrolls when the screen is short; the buttons stay at the foot, always in reach.
          Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.me_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in people + listOfNotNull(draft?.takeIf { d -> people.none { it.who == d.who } })) {
                    val chosen = p.who == who
                    val label = p.name.ifEmpty { stringResource(if (p.guest) R.string.me_new_guest else R.string.me_new_user) }
                    Row(
                        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(if (chosen) Palette.Ink else Palette.Tint)
                            .clickable { who = p.who }.semantics { role = Role.RadioButton; selected = chosen; contentDescription = label }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PieceIcon(pieces.styleOf(p), 26.dp, label = "")
                        Text(label + if (p.guest && p.name.isNotEmpty()) " · " + stringResource(R.string.me_guest) else "", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            color = if (chosen) Palette.Cream else Palette.Ink, maxLines = 1)
                    }
                }
                AddChip(stringResource(R.string.me_add_user)) { add(guest = false) }
                AddChip(stringResource(R.string.me_add_guest)) { add(guest = true) }
            }
            if (current == null) Text(stringResource(R.string.me_pick_first), fontSize = 16.sp, color = Palette.Ink2)
            else {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(Couple.MAX_NAME) }, singleLine = true,
                    label = { Text(stringResource(R.string.me_name)) },
                    textStyle = TextStyle(fontSize = 18.sp, color = Palette.Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Ink, unfocusedBorderColor = Palette.Line2, cursorColor = Palette.Ink),
                )
                Text(stringResource(R.string.me_shape), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sh in pieces.shapeIds) {
                        val style = pieces.style(sh, color)
                        Pick(sh == shape, style.shapeName, onClick = { shape = sh }) { PieceIcon(style, 28.dp, label = "") }
                    }
                }
                Text(stringResource(R.string.me_color), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in pieces.colorIds) {
                        val style = pieces.style(shape, c)
                        Pick(c == color, style.colorName, onClick = { color = c }) {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(style.base).border(BorderStroke(1.dp, Palette.Ink), CircleShape))
                        }
                    }
                }
                if (current.other) Text(
                    stringResource(if (current.guest) R.string.me_guest_help else R.string.me_other_help), fontSize = 15.sp, color = Palette.Ink2,
                )
            }
          }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(stringResource(R.string.me_save), primary = true, enabled = current != null && name.isNotBlank(), modifier = Modifier.weight(2f)) {
                    val c = current ?: return@Action
                    onSave(c.copy(name = name.trim(), shape = shape, color = color, at = System.currentTimeMillis()))
                }
                // One of the others, already kept: it can be removed (the two of us cannot).
                if (current != null && current.other && people.any { it.who == current.who }) {
                    Action(stringResource(R.string.me_remove), modifier = Modifier.weight(1f)) { onRemove(current.who) }
                }
                Action(stringResource(R.string.result_cancel), modifier = Modifier.weight(1f), onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun AddChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).border(BorderStroke(1.5.dp, Palette.Ink), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).semantics { role = Role.Button; contentDescription = label }.padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1) }
}

/** One choice of a row (a shape, a colour): the chosen one wears a dark ring. */
@Composable
private fun Pick(chosen: Boolean, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Tint)
            .border(BorderStroke(if (chosen) 3.dp else 1.dp, if (chosen) Palette.Ink else Palette.Line2), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).semantics { role = Role.RadioButton; selected = chosen; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Action(label: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(if (primary && enabled) Palette.Ink else Palette.Tint)
            .clickable(enabled = enabled, onClick = onClick).semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (primary && enabled) Palette.Cream else if (enabled) Palette.Ink else Palette.Ink2) }
}

/** A game to tie a note to: its id and name. */
data class NoteGame(val id: String, val name: String)

/**
 * A note for the other one (1.9.9): a few words, a small drawing in the writer's colour, and the game it waits for (it shows on the TV
 * when that game was played), or none (it shows on the TV's home). Sent to the TV, or kept here until the TV is reached.
 */
@Composable
internal fun NoteDialog(
    /** Who it can be for (everyone but the writer), and who to begin with. */
    recipients: List<Person>,
    initialTo: String?,
    pieces: PiecesConfig,
    color: androidx.compose.ui.graphics.Color,
    games: List<NoteGame>,
    initialGame: String?,
    onSend: (to: String, game: String?, text: String, doodle: List<IntArray>) -> Unit,
    onDismiss: () -> Unit,
) {
    var to by remember { mutableStateOf(initialTo?.takeIf { w -> recipients.any { it.who == w } } ?: recipients.firstOrNull()?.who.orEmpty()) }
    val toName = recipients.firstOrNull { it.who == to }?.name.orEmpty()
    var game by remember { mutableStateOf(initialGame) }
    var text by remember { mutableStateOf("") }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var size by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(12.dp).widthIn(max = 880.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Cream)
                .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.note_title, toName), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            // For whom (when there are more than one to choose from).
            if (recipients.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in recipients) Chip(p.name, to == p.who, piece = { PieceIcon(pieces.styleOf(p), 20.dp, label = "") }) { to = p.who }
            }
            // Side by side on a phone held sideways (keyboard mode), one under the other on one held upright (the home).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
                Column(Modifier.widthIn(min = 280.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.note_when), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(stringResource(R.string.note_no_game), game == null) { game = null }
                        for (g in games) Chip(g.name, game == g.id) { game = g.id }
                    }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it.take(Notes.MAX_TEXT) },
                        label = { Text(stringResource(R.string.note_text)) }, minLines = 2, maxLines = 3,
                        textStyle = TextStyle(fontSize = 18.sp, color = Palette.Ink), modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Ink, unfocusedBorderColor = Palette.Line2, cursorColor = Palette.Ink),
                    )
                }
                Column(Modifier.widthIn(min = 280.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.note_doodle), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.weight(1f))
                        if (strokes.isNotEmpty()) Text(
                            stringResource(R.string.note_clear), fontSize = 15.sp, color = Palette.Red, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { strokes.clear() }.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    val description = stringResource(R.string.note_doodle)
                    Canvas(
                        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).background(androidx.compose.ui.graphics.Color(0xFFFFF8E1))
                            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(14.dp)).testTag(DOODLE_TAG)
                            .semantics { contentDescription = description }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume()
                                    if (strokes.size >= Notes.MAX_STROKES) return@awaitEachGesture
                                    val points = mutableStateListOf(down.position)
                                    strokes += points
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        ch.consume()
                                        // A point every few pixels: enough for a drawing, few enough to send.
                                        if ((ch.position - points.last()).getDistance() >= 4f && strokes.sumOf { it.size } < Notes.MAX_POINTS) points += ch.position
                                    }
                                }
                            },
                    ) {
                        size = this.size
                        val w = 3.5.dp.toPx()
                        for (s in strokes) {
                            if (s.size == 1) { drawCircle(color, radius = w / 2, center = s[0]); continue }
                            val p = Path().apply { s.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                            drawPath(p, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }
          }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(stringResource(R.string.note_send), primary = true, enabled = to.isNotEmpty() && (text.isNotBlank() || strokes.isNotEmpty()), modifier = Modifier.weight(2f)) {
                    val w = size.width.coerceAtLeast(1f); val h = size.height.coerceAtLeast(1f)
                    val doodle = strokes.map { s -> IntArray(s.size * 2) { i -> val o = s[i / 2]; ((if (i % 2 == 0) o.x / w else o.y / h) * 1000f).roundToInt().coerceIn(0, 1000) } }
                    onSend(to, game, text.trim(), doodle)
                }
                Action(stringResource(R.string.result_cancel), modifier = Modifier.weight(1f), onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun Chip(label: String, chosen: Boolean, piece: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(if (chosen) Palette.Ink else Palette.Tint)
            .clickable(onClick = onClick).semantics(mergeDescendants = true) { role = Role.RadioButton; selected = chosen; contentDescription = label }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        piece?.invoke()
        Text(label, fontSize = 16.sp, color = if (chosen) Palette.Cream else Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
