package app.couch.alhasapa.dnd

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.ui.theme.Palette
import kotlinx.coroutines.delay

/** How long the chip stays up if nobody touches it. */
private const val CHIP_VISIBLE_MS = 15_000L

/**
 * Once per evening, while the screen is cast, a small chip suggests turning on Do Not Disturb so notifications do not pop up
 * on the TV. Tapping it opens the phone's Do Not Disturb settings; the cross dismisses it. It is only a nudge: the app never
 * changes the setting itself (it has no permission to, and should not).
 *
 * The check runs when this enters the screen and again each time the screen is resumed (casting may start later).
 */
@Composable
fun DndChipHost(modifier: Modifier = Modifier, signals: DndSignals = DndSignals.real(LocalContext.current), recheck: Int = 0) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(recheck) {
        if (!show && DndPrompt.shouldShow(signals)) {
            signals.markShown(DndPrompt.eveningKey())            // once per evening, even if it is ignored
            show = true
            delay(CHIP_VISIBLE_MS)
            show = false
        }
    }
    if (show) {
        val label = stringResource(R.string.dnd_chip)
        val hint = stringResource(R.string.dnd_chip_hint)
        val close = stringResource(R.string.dnd_close)
        Row(
            modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Palette.Card)
                .border(BorderStroke(2.dp, Palette.Ink), RoundedCornerShape(28.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .heightIn(min = 56.dp)
                    .widthIn(min = 48.dp)
                    .clickable { DndPrompt.openSettings(context); show = false }
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clearAndSetSemantics { role = Role.Button; contentDescription = "$label. $hint"; onClick { DndPrompt.openSettings(context); show = false; true } },
                contentAlignment = Alignment.Center,
            ) {
                Text("$label · $hint", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Palette.Ink)
            }
            Box(
                Modifier
                    .heightIn(min = 56.dp)
                    .widthIn(min = 56.dp)
                    .clickable { show = false }
                    .clearAndSetSemantics { role = Role.Button; contentDescription = close; onClick { show = false; true } },
                contentAlignment = Alignment.Center,
            ) { Text("✕", fontSize = 22.sp, color = Palette.Ink2) }
        }
    }
}
