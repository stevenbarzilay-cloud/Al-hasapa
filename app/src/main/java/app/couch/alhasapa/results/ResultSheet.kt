package app.couch.alhasapa.results

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.couch.alhasapa.tv.LocalTv
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.tv.tvTarget
import app.couch.alhasapa.catalog.Game
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.PieceStyle
import app.couch.alhasapa.scoring.Result
import app.couch.alhasapa.scoring.Scoring
import app.couch.alhasapa.scoring.jsNumber
import app.couch.alhasapa.ui.PieceIcon
import app.couch.alhasapa.ui.theme.Palette
import org.json.JSONObject

/** Everything the sheet needs to know about the game it is for. */
data class ResultSupport(
    val game: Game,
    /** A snapshot of the state (for the history the points depend on, and what is already saved today). */
    val state: JSONObject,
    val day: String,
)

/** The two people, for the sheet's labels and the "who cracked it" buttons. */
data class Couple(
    val nameA: String, val nameB: String, val pieceA: PieceStyle, val pieceB: PieceStyle,
    /** The others who are here this evening (1.10): they can be the ones who cracked it too. */
    val others: List<CreditOther> = emptyList(),
) {
    fun name(who: String?) = if (who == "b") nameB else nameA
}

/** One of the others, for the "who cracked it" buttons: their id, name and piece. */
data class CreditOther(val id: String, val name: String, val piece: PieceStyle)

private val SheetText = 20.sp

/**
 * The result of a game, filled in from what the game copied (or typed by hand), with the points it is worth shown before
 * saving. Used in the player (landscape, over the game) and by the share target (portrait, a screen of its own).
 * The logic is the prototype's `openResult` / `openDuel`; the rules are in [ResultDraft].
 */
@Composable
fun ResultSheet(
    support: ResultSupport,
    couple: Couple,
    /** Head to head: whose result this is ("a" / "b"). Null for the games we play together. */
    who: String?,
    initialText: String,
    captured: Boolean,
    onSave: (ResultInput) -> Unit,
    onClear: (() -> Unit)?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** The text was read off the game's end screen rather than copied by the game (1.9.10). */
    fromScreen: Boolean = false,
) {
    val game = support.game
    val kind = ResultDraft.kindOf(game)
    val duel = game.isDuel
    val existing = CouchState.entry(support.state, support.day, game.id)
    val existingResult: Result? = if (duel) null else Result.fromJson(existing?.optJSONObject("r"))

    var text by remember { mutableStateOf(initialText) }
    var manual by remember { mutableStateOf(existingResult?.takeIf { it.raw.isNullOrEmpty() }?.let { if (kind == "time") Scoring.fmtT(it.v) else jsNumber(it.v).toString() }.orEmpty()) }
    var fail by remember { mutableStateOf(existingResult?.fail == true) }
    var credit by remember { mutableStateOf(existing?.optString("w").orEmpty()) }

    val log = support.state.optJSONObject("log") ?: JSONObject()
    val draft = remember(text, manual, fail, support) { ResultDraft.evaluate(game, text, manual, fail, log, support.day) }
    val canClear = onClear != null && (if (duel) existing?.optJSONObject("d")?.optJSONObject(who ?: "") != null else existingResult != null)
    val save = { onSave(ResultInput(text = text, manual = manual, fail = fail, credit = if (duel) null else credit)) }

    // On a TV (1.9.7): nothing here can be typed with a remote, so the sheet is buttons only (the number has − and +), the arrows stay
    // inside it, the focus begins on "save" (one OK saves what the game handed over), and what the phone types goes into the number.
    val tv = LocalTv.current
    if (tv != null) {
        val latestSave by rememberUpdatedState(save)
        val latestCancel by rememberUpdatedState(onCancel)
        DisposableEffect(tv) {
            tv.modalPrefix = SHEET_PREFIX
            tv.focus(SAVE_ID)
            tv.textSink = { d -> typed(d, kind, { manual }, { manual = it }, onEnter = { latestSave() }, onEscape = { latestCancel() }) }
            onDispose { tv.modalPrefix = null; tv.textSink = null }
        }
    }

    val title = if (duel) stringResource(R.string.result_title_duel, game.n, couple.name(who)) else stringResource(R.string.result_title, game.n)
    val preview = when {
        draft.result != null && duel -> stringResource(R.string.result_detected_duel, Scoring.fmtVal(kind, draft.result))
        draft.result != null -> stringResource(R.string.result_detected, Scoring.fmtVal(kind, draft.result), draft.points)
        text.isNotBlank() -> stringResource(R.string.result_unrecognised)
        else -> ""
    }

    if (tv != null) {
        // A TV's screen is wide and low (about 960 by 540): the sheet stands whole on it, in two columns at the top (what the game handed
        // over and what it is worth at the right, the number by hand at the left), then a row each for "we did not make it", who cracked
        // it, and the buttons.
        Column(
            modifier
                .widthIn(max = 900.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.Cream)
                .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (captured) Text(stringResource(if (fromScreen) R.string.result_read_screen else R.string.result_captured), fontSize = 16.sp, color = Palette.Ink2)
                    if (text.isNotBlank()) {
                        // What the game handed over, to read (a TV has nothing to paste with).
                        Text(
                            text.trim(), fontSize = 18.sp, color = Palette.Ink, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Card).border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    } else Text(stringResource(R.string.result_tv_no_text), fontSize = 17.sp, color = Palette.Ink2)
                    if (preview.isNotEmpty()) Text(preview, fontSize = SheetText, fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.semantics { contentDescription = preview })
                }
                ManualSteppers(kind, manual, draft.parsed, onChange = { manual = it }, modifier = Modifier.weight(1f))
            }
            FailRow(duel, fail, onTv = true) { fail = it }
            if (!duel) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.result_credit), fontSize = SheetText, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.padding(end = 6.dp))
                CreditChips(couple, credit, Modifier.weight(1f)) { credit = if (credit == it) "" else it }
            }
            Actions(canClear, Modifier.padding(top = 2.dp), onSave = save, onClear = onClear, onCancel = onCancel)
        }
        return
    }

    Column(
        modifier
            .widthIn(max = 640.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Cream)
            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(20.dp))
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        if (captured) Text(stringResource(R.string.result_captured), fontSize = SheetText, color = Palette.Ink2)

        Field(
            label = stringResource(R.string.result_paste_label), help = stringResource(R.string.result_paste_help),
            value = text, onChange = { text = it }, singleLine = false, keyboard = KeyboardType.Text, minHeight = 96.dp,
        )
        if (preview.isNotEmpty()) Text(preview, fontSize = SheetText, fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.semantics { contentDescription = preview })

        Field(
            label = stringResource(R.string.result_manual, Scoring.KIND_LABEL[kind] ?: ""), help = null,
            value = manual, onChange = { manual = it }, singleLine = true,
            keyboard = if (kind == "time") KeyboardType.Text else KeyboardType.Decimal, minHeight = 56.dp,
        )
        FailRow(duel, fail, onTv = false) { fail = it }

        if (!duel) {
            Text(stringResource(R.string.result_credit), fontSize = SheetText, fontWeight = FontWeight.Bold, color = Palette.Ink)
            // The two of us and "together" share the row; with others here too (1.10), the buttons wrap to as many rows as they need.
            if (couple.others.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CreditChips(couple, credit, Modifier.weight(1f)) { credit = if (credit == it) "" else it }
            } else androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(),
            ) {
                CreditChips(couple, credit, Modifier) { credit = if (credit == it) "" else it }
            }
        }

        Spacer(Modifier.height(4.dp))
        Actions(canClear, Modifier, onSave = save, onClear = onClear, onCancel = onCancel)
    }
}

/** "We did not make it this time": a tick, and on a TV a button of the remote's too. */
@Composable
private fun FailRow(duel: Boolean, fail: Boolean, onTv: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (onTv) 48.dp else 56.dp)
            .then(if (onTv) Modifier.clip(RoundedCornerShape(12.dp)).tvTarget(SHEET_PREFIX + "fail", null) { onChange(!fail) } else Modifier)
            .toggleable(value = fail, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = fail, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Palette.Ink, checkmarkColor = Palette.Cream))
        Spacer(Modifier.padding(horizontal = 6.dp))
        Text(stringResource(if (duel) R.string.result_fail_duel else R.string.result_fail), fontSize = SheetText, color = Palette.Ink)
    }
}

/** Who cracked it: one of the two, or both together (pressing the chosen one again takes the choice back). Inside a row. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.CreditChips(couple: Couple, credit: String, each: Modifier, onPick: (String) -> Unit) {
    CreditChip("a", couple.nameA, couple.pieceA, credit == "a", each) { onPick("a") }
    CreditChip("b", couple.nameB, couple.pieceB, credit == "b", each) { onPick("b") }
    for (o in couple.others) CreditChip(o.id, o.name, o.piece, credit == o.id, each) { onPick(o.id) }
    CreditChip("t", stringResource(R.string.result_credit_both), null, credit == "t", each) { onPick("t") }
}

@Composable
private fun Actions(canClear: Boolean, modifier: Modifier, onSave: () -> Unit, onClear: (() -> Unit)?, onCancel: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        SheetAction(SAVE_ID, stringResource(R.string.result_save), primary = true, modifier = Modifier.weight(2f), onClick = onSave)
        if (canClear) SheetAction(SHEET_PREFIX + "clear", stringResource(R.string.result_clear), modifier = Modifier.weight(1.5f)) { onClear?.invoke() }
        SheetAction(SHEET_PREFIX + "cancel", stringResource(R.string.result_cancel), modifier = Modifier.weight(1f), onClick = onCancel)
    }
}

@Composable
private fun Field(label: String, help: String?, value: String, onChange: (String) -> Unit, singleLine: Boolean, keyboard: KeyboardType, minHeight: androidx.compose.ui.unit.Dp) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = SheetText, fontWeight = FontWeight.Bold, color = Palette.Ink)
        if (help != null) Text(help, fontSize = 18.sp, color = Palette.Ink2)
        OutlinedTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).semantics { contentDescription = label },
            textStyle = TextStyle(fontSize = SheetText, color = Palette.Ink),
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.Ink, unfocusedBorderColor = Palette.Line2, cursorColor = Palette.Ink,
                focusedContainerColor = Palette.Card, unfocusedContainerColor = Palette.Card,
            ),
        )
    }
}

/** The result sheet's buttons on a TV: their ids begin with this, and the arrows stay among them while it is up. */
const val SHEET_PREFIX = "sheet:"
const val SAVE_ID = SHEET_PREFIX + "save"

/**
 * The number by hand, with the remote: − and + (and for a time, minutes and seconds apart). Starts from what the game handed over,
 * if anything, so a mistaken reading is mended with a press or two.
 */
@Composable
private fun ManualSteppers(kind: String, manual: String, parsed: app.couch.alhasapa.scoring.Parsed?, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.result_manual, Scoring.KIND_LABEL[kind] ?: ""), fontSize = SheetText, fontWeight = FontWeight.Bold, color = Palette.Ink)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (kind == "time") {
                    val secs = (Scoring.parseResult(manual, "time")?.v ?: parsed?.v ?: 0.0).toInt()
                    fun set(s: Int) = onChange(Scoring.fmtT(s.coerceIn(0, 5 * 3600).toDouble()))
                    Step("min-less", "−", stringResource(R.string.result_tv_min_less)) { set(secs - 60) }
                    Text(Scoring.fmtT(secs.toDouble()), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Step("min-more", "+", stringResource(R.string.result_tv_min_more)) { set(secs + 60) }
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Step("sec-less", "−", stringResource(R.string.result_tv_sec_less)) { set(secs - 1) }
                    Step("sec-more", "+", stringResource(R.string.result_tv_sec_more)) { set(secs + 1) }
                } else {
                    val v = manual.trim().toDoubleOrNull() ?: parsed?.v ?: 0.0
                    val top = when (kind) { "guesses" -> (parsed?.max ?: 6) + 1; "mistakes" -> 4; else -> 100_000 }
                    fun set(n: Double) = onChange(app.couch.alhasapa.scoring.jsNumber(n.coerceIn(0.0, top.toDouble())).toString())
                    Step("less", "−", stringResource(R.string.tvset_smaller)) { set(kotlin.math.floor(v) - 1) }
                    Text(app.couch.alhasapa.scoring.jsNumber(v).toString(), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Step("more", "+", stringResource(R.string.tvset_larger)) { set(kotlin.math.floor(v) + 1) }
                }
            }
        }
        Text(stringResource(R.string.result_tv_phone), fontSize = 16.sp, color = Palette.Ink2)
    }
}

@Composable
private fun Step(key: String, glyph: String, label: String, onClick: () -> Unit) {
    val tv = LocalTv.current
    val id = SHEET_PREFIX + key
    androidx.compose.foundation.layout.Box(
        Modifier.heightIn(min = 52.dp).widthIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Tint).tvTarget(id, label, onClick)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (tv?.isFocused(id) == true) Palette.Cream else Palette.Ink) }
}

/**
 * A key the phone typed while the sheet is up: digits (and a colon, a point, a slash) go into the number, Backspace takes one off,
 * Enter saves, Escape closes. Anything else is ignored.
 */
internal fun typed(d: app.couch.alhasapa.tv.Delivery, kind: String, get: () -> String, set: (String) -> Unit, onEnter: () -> Unit, onEscape: () -> Unit) {
    fun add(s: String) { val keep = s.filter { it.isDigit() || it == ':' || it == '.' || it == '/' }; if (keep.isNotEmpty()) set((get() + keep).take(16)) }
    when (d) {
        is app.couch.alhasapa.tv.Delivery.Text -> add(d.text)
        is app.couch.alhasapa.tv.Delivery.Code -> when (val k = d.keyCode) {
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> add(('0' + (k - android.view.KeyEvent.KEYCODE_0)).toString())
            android.view.KeyEvent.KEYCODE_SEMICOLON -> if (d.shift) add(":")
            android.view.KeyEvent.KEYCODE_PERIOD -> add(".")
            android.view.KeyEvent.KEYCODE_SLASH -> add("/")
            android.view.KeyEvent.KEYCODE_DEL -> set(get().dropLast(1))
            android.view.KeyEvent.KEYCODE_ENTER -> onEnter()
            android.view.KeyEvent.KEYCODE_ESCAPE -> onEscape()
            else -> {}
        }
        else -> {}
    }
    if (kind == "time" && get().count { it == ':' } > 2) set(get().dropLast(1))
}

@Composable
private fun CreditChip(key: String, label: String, piece: PieceStyle?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .heightIn(min = 56.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Palette.Ink else Palette.Tint)
            .tvTarget(SHEET_PREFIX + "credit-" + key, label, onClick)
            .padding(horizontal = 8.dp)
            // One clean description, not the label plus the piece icon's own.
            .clearAndSetSemantics { role = Role.RadioButton; this.selected = selected; contentDescription = label; onClick { onClick(); true } },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (piece != null) PieceIcon(piece, 28.dp, label = "")
        Text(label, fontSize = SheetText, fontWeight = FontWeight.Medium, color = if (selected) Palette.Cream else Palette.Ink, maxLines = 1)
    }
}

@Composable
private fun SheetAction(id: String, label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier
            .heightIn(min = 56.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) Palette.Ink else Palette.Tint)
            .tvTarget(id, label, onClick)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = SheetText, fontWeight = FontWeight.SemiBold, color = if (primary) Palette.Cream else Palette.Ink, modifier = Modifier.padding(horizontal = 8.dp))
    }
}
