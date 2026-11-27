package app.couch.alhasapa.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.couch.alhasapa.R
import app.couch.alhasapa.data.PieceStyle
import app.couch.alhasapa.tv.tvTarget
import app.couch.alhasapa.ui.theme.Palette

/** Someone who already played this game alone today (1.11): the challenge the sheet offers. [two]: one of the two of us. */
data class Challenger(val name: String, val piece: PieceStyle, val two: Boolean)

const val PLAY_TOGETHER_TAG = "play-together"
const val PLAY_SOLO_TAG = "play-solo"
const val PLAY_ELSEWHERE_TAG = "play-elsewhere"

/**
 * Together or alone? (1.11) Asked on the phone when a game opens, once a game and a day. Together: as always, on the couch's page and
 * points. Alone: in the person's own corner of the app (their own browser profile, so a shared login's puzzle stays open for the
 * evening), and the result is sealed until whoever else plays it alone today has played. When someone already did, the sheet says so
 * and "alone" takes the challenge. "Played it elsewhere already" types a result in without opening the game (a computer, say).
 */
@Composable
fun PlayModeDialog(
    gameName: String,
    challengers: List<Challenger>,
    onTogether: () -> Unit,
    onSolo: () -> Unit,
    /** Null where the game is not about to open (a result shared from elsewhere): no "played it elsewhere". */
    onElsewhere: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(22.dp)).background(Palette.Cream).border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(22.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(gameName, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
            Text(stringResource(R.string.play_mode_question), fontSize = 17.sp, color = Palette.Ink2, textAlign = TextAlign.Center)
            for (c in challengers) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Tint).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PieceIcon(c.piece, 20.dp, label = c.name)
                    Text(
                        stringResource(if (c.two) R.string.play_mode_challenge else R.string.play_mode_challenge_other, c.name),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeCard(
                    "🛋️", stringResource(R.string.play_mode_together), stringResource(R.string.play_mode_together_sub),
                    Palette.Card, Palette.Ink, PLAY_TOGETHER_TAG, Modifier.weight(1f), onTogether,
                )
                ModeCard(
                    "⚔️", stringResource(R.string.play_mode_solo),
                    stringResource(if (challengers.isEmpty()) R.string.play_mode_solo_sub else R.string.play_mode_solo_challenge),
                    Palette.Ink, Palette.Cream, PLAY_SOLO_TAG, Modifier.weight(1f), onSolo,
                )
            }
            if (onElsewhere != null) Text(
                stringResource(R.string.play_mode_elsewhere), fontSize = 16.sp, color = Palette.Ink2, textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).heightIn(min = 44.dp).tvTarget(onElsewhere).testTag(PLAY_ELSEWHERE_TAG)
                    .semantics { role = Role.Button }.padding(horizontal = 12.dp, vertical = 11.dp),
            )
            if (onElsewhere != null) Text(stringResource(R.string.play_mode_hint), fontSize = 13.sp, color = Palette.Ink2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ModeCard(
    mark: String, title: String, sub: String, background: Color, ink: Color, tag: String, modifier: Modifier, onClick: () -> Unit,
) {
    Column(
        modifier
            .defaultMinSize(minHeight = 132.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .border(BorderStroke(2.dp, Palette.Ink), RoundedCornerShape(16.dp))
            .tvTarget(onClick)
            .testTag(tag)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "$title. $sub" }
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(mark, fontSize = 30.sp)
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink, textAlign = TextAlign.Center)
        Text(sub, fontSize = 14.sp, color = ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
