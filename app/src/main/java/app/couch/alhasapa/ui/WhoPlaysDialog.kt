package app.couch.alhasapa.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.couch.alhasapa.tv.tvTarget
import app.couch.alhasapa.data.PieceStyle
import app.couch.alhasapa.ui.theme.Palette

/**
 * "מי משחק?" before a head-to-head game: each person plays alone, in their own LinkedIn account. The two big buttons wear the
 * pieces (the red circle and the pink triangle, or whatever they picked).
 */
@Composable
fun WhoPlaysDialog(
    gameName: String,
    nameA: String,
    nameB: String,
    pieceA: PieceStyle,
    pieceB: PieceStyle,
    onPick: (who: String) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.who_plays_title),
    hint: String = stringResource(R.string.who_plays_hint),
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Palette.Cream).border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(20.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(gameName, fontSize = 18.sp, color = Palette.Ink2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(hint, fontSize = 15.sp, color = Palette.Ink2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WhoButton(nameA, pieceA) { onPick("a") }
                WhoButton(nameB, pieceB) { onPick("b") }
            }
        }
    }
}

@Composable
private fun WhoButton(name: String, piece: PieceStyle, onClick: () -> Unit) {
    val label = stringResource(R.string.who_plays_as, name)
    Column(
        Modifier
            .defaultMinSize(minWidth = 128.dp, minHeight = 128.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Card)
            .border(BorderStroke(2.dp, Palette.Ink), RoundedCornerShape(16.dp))
            .tvTarget(onClick)
            .padding(16.dp)
            .semantics { role = Role.Button; contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PieceIcon(piece, 64.dp, Modifier.size(64.dp), label = "")
        Spacer(Modifier.height(10.dp))
        Text(name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
    }
}
