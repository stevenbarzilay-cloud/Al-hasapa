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
data class Couple(val nameA: String, val nameB: String, val pieceA: PieceStyle, val pieceB: PieceStyle) {
    fun name(who: String?) = if (who == "b") nameB else nameA
}

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
        Text(
            if (duel) stringResource(R.string.result_title_duel, game.n, couple.name(who)) else stringResource(R.string.result_title, game.n),
            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink,
        )
        if (captured) Text(stringResource(R.string.result_captured), fontSize = SheetText, color = Palette.Ink2)

        Field(
            label = stringResource(R.string.result_paste_label), help = stringResource(R.string.result_paste_help),
            value = text, onChange = { text = it }, singleLine = false, keyboard = KeyboardType.Text, minHeight = 96.dp,
        )

        val preview = when {
            draft.result != null && duel -> stringResource(R.string.result_detected_duel, Scoring.fmtVal(kind, draft.result))
            draft.result != null -> stringResource(R.string.result_detected, Scoring.fmtVal(kind, draft.result), draft.points)
            text.isNotBlank() -> stringResource(R.string.result_unrecognised)
            else -> ""
        }
        if (preview.isNotEmpty()) Text(preview, fontSize = SheetText, fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.semantics { contentDescription = preview })

        Field(
            label = stringResource(R.string.result_manual, Scoring.KIND_LABEL[kind] ?: ""), help = null,
            value = manual, onChange = { manual = it }, singleLine = true,
            keyboard = if (kind == "time") KeyboardType.Text else KeyboardType.Decimal, minHeight = 56.dp,
        )

        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(value = fail, role = Role.Checkbox, onValueChange = { fail = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = fail, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Palette.Ink, checkmarkColor = Palette.Cream))
            Spacer(Modifier.padding(horizontal = 6.dp))
            Text(stringResource(if (duel) R.string.result_fail_duel else R.string.result_fail), fontSize = SheetText, color = Palette.Ink)
        }

        if (!duel) {
            Text(stringResource(R.string.result_credit), fontSize = SheetText, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CreditChip(couple.nameA, couple.pieceA, credit == "a", Modifier.weight(1f)) { credit = if (credit == "a") "" else "a" }
                CreditChip(couple.nameB, couple.pieceB, credit == "b", Modifier.weight(1f)) { credit = if (credit == "b") "" else "b" }
                CreditChip(stringResource(R.string.result_credit_both), null, credit == "t", Modifier.weight(1f)) { credit = if (credit == "t") "" else "t" }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SheetAction(stringResource(R.string.result_save), primary = true, modifier = Modifier.weight(2f)) {
                onSave(ResultInput(text = text, manual = manual, fail = fail, credit = if (duel) null else credit))
            }
            if (canClear) SheetAction(stringResource(R.string.result_clear), modifier = Modifier.weight(1.5f)) { onClear?.invoke() }
            SheetAction(stringResource(R.string.result_cancel), modifier = Modifier.weight(1f), onClick = onCancel)
        }
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

@Composable
private fun CreditChip(label: String, piece: PieceStyle?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .heightIn(min = 56.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Palette.Ink else Palette.Tint)
            .clickable(onClick = onClick)
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
private fun SheetAction(label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier
            .heightIn(min = 56.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) Palette.Ink else Palette.Tint)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = SheetText, fontWeight = FontWeight.SemiBold, color = if (primary) Palette.Cream else Palette.Ink, modifier = Modifier.padding(horizontal = 8.dp))
    }
}
