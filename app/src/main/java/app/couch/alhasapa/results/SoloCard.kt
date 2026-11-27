package app.couch.alhasapa.results

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.tv.tvTarget
import app.couch.alhasapa.ui.PieceIcon
import app.couch.alhasapa.ui.theme.Palette
import kotlinx.coroutines.delay

/** The tag of the card's button, for the tests. */
const val SOLO_OK_TAG = "solo-ok"

/**
 * The card after a game played alone is saved (1.11). Sealed: "✉️ נחתם במעטפה", the result, and who it waits for. The reveal: the
 * envelopes open one after the other (each person's result, best last), and the crown goes to whoever did best, or stays in the middle.
 * [other] is who the result waits for when nobody else has played it (the other one of the two of us; null for anyone else).
 */
@Composable
fun SoloCard(o: SoloOutcome, other: String?, reduceMotion: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val lines = listOf(o.mine) + o.others
    // The reveal comes line by line (none of it when motion is off).
    var shown by remember(o) { mutableStateOf(if (reduceMotion || !o.revealed) lines.size + 1 else 0) }
    LaunchedEffect(o) { while (shown <= lines.size) { delay(REVEAL_STEP_MS); shown++ } }

    Column(
        modifier
            .widthIn(max = 520.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.Cream)
            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val waitingForResult = !o.revealed && o.mine.hasResult && o.others.isNotEmpty() && o.others.none { it.hasResult }
        val title = when {
            o.revealed -> stringResource(R.string.solo_reveal_title)
            waitingForResult -> stringResource(R.string.solo_waiting_title)
            else -> stringResource(R.string.solo_sealed_title)
        }
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
        Text(o.gameName, fontSize = 18.sp, color = Palette.Ink2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(2.dp))

        lines.forEachIndexed { i, l ->
            AnimatedVisibility(
                visible = shown > i,
                enter = if (reduceMotion) fadeIn(tween(0)) else fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 2 },
            ) {
                SoloRow(l, crown = o.revealed && o.winner == l.who, mine = o.viewer && i == 0)
            }
        }

        val verdict = when {
            !o.revealed -> when {
                !o.mine.hasResult -> stringResource(R.string.solo_played_only)
                o.others.isEmpty() -> if (other != null) stringResource(R.string.solo_until, other) else stringResource(R.string.solo_until_anyone)
                waitingForResult -> stringResource(R.string.solo_until_result)
                else -> stringResource(R.string.solo_until_anyone)
            }
            o.winner == "t" -> stringResource(R.string.solo_tie)
            o.viewer && o.winner == o.mine.who -> stringResource(R.string.solo_crown_mine)
            else -> stringResource(R.string.solo_crown, lines.firstOrNull { it.who == o.winner }?.name.orEmpty())
        }
        AnimatedVisibility(
            visible = shown > lines.size,
            enter = if (reduceMotion || !o.revealed) fadeIn(tween(0)) else fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.6f),
        ) {
            Text(verdict, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.solo_ok), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Cream, textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Ink)
                .tvTarget(SOLO_OK_TAG, null, onDone)
                .testTag(SOLO_OK_TAG)
                .padding(vertical = 13.dp),
        )
    }
}

@Composable
private fun SoloRow(l: SoloLine, crown: Boolean, mine: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (crown) Palette.Tint else Palette.Card)
            .border(BorderStroke(if (crown) 2.dp else 1.dp, if (crown) Palette.Ink else Palette.Line2), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PieceIcon(l.piece, 26.dp, label = l.name)
        Text(l.name, fontSize = 20.sp, fontWeight = if (mine) FontWeight.Bold else FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.weight(1f))
        val what = when {
            l.sealed && l.hasResult -> stringResource(R.string.solo_in_envelope)
            l.sealed || !l.hasResult -> stringResource(if (l.who in Couple.WHO) R.string.solo_no_result_yet else R.string.solo_no_result_yet_other)
            else -> l.result
        }
        Text(what, fontSize = if (l.sealed || !l.hasResult) 17.sp else 22.sp, fontWeight = FontWeight.Bold, color = if (l.sealed || !l.hasResult) Palette.Ink2 else Palette.Ink)
        if (crown) Text("👑", fontSize = 22.sp)
    }
}

private const val REVEAL_STEP_MS = 450L
