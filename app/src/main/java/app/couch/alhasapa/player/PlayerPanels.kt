package app.couch.alhasapa.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.display.DisplayMode
import app.couch.alhasapa.display.DisplayProfile
import app.couch.alhasapa.ui.PieceIcon
import app.couch.alhasapa.ui.theme.Palette

/** Panels read from the couch: nothing in them is below 20sp. */
private val PanelText = 20.sp

/** The panel beside the game (the one on the right, where a Hebrew reader begins): whose couch, which game, how long. */
@Composable
fun IdentityPanel(width: Dp, title: String, elapsed: String, modifier: Modifier = Modifier) {
    PanelFrame(width, modifier) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
            modifier = Modifier.size(minOf(96.dp, width - 24.dp)),
        )
        Text(stringResource(R.string.panel_logo), fontSize = PanelText, color = Palette.Ink2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center, lineHeight = 30.sp)
        Spacer(Modifier.height(12.dp))
        // Digits read left to right even in a Hebrew sentence, so the clock sits in its own left-to-right run.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(elapsed, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.semantics { contentDescription = elapsed })
        }
    }
}

/** The panel on the other side: the evening so far and the two of us. */
@Composable
fun ProgressPanel(width: Dp, info: PlayerInfo, modifier: Modifier = Modifier, bottom: @Composable () -> Unit = {}) {
    PanelFrame(width, modifier) {
        Text("${info.done}", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Palette.Red, lineHeight = 60.sp)
        Text(stringResource(R.string.panel_progress, info.total), fontSize = PanelText, color = Palette.Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.panel_points, info.points), fontSize = PanelText, fontWeight = FontWeight.SemiBold, color = Palette.Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Person(info.pieceA, info.nameA, active = info.who == "a")
        Spacer(Modifier.height(8.dp))
        Person(info.pieceB, info.nameB, active = info.who == "b")
        Spacer(Modifier.height(16.dp))
        bottom()
    }
}

/** The big button at the foot of the progress panel. */
@Composable
fun PanelButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Ink)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Cream)
    }
}

@Composable
private fun Person(style: app.couch.alhasapa.data.PieceStyle, name: String, active: Boolean) {
    val playing = stringResource(R.string.panel_playing)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (active) Modifier.border(BorderStroke(2.dp, Palette.Ink), RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = if (active) "$name, $playing" else name },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        PieceIcon(style, 32.dp)
        Text(name, fontSize = PanelText, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = Palette.Ink, maxLines = 1)
    }
}

@Composable
private fun PanelFrame(width: Dp, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // Both panels are right to left inside, whichever side of the screen they stand on.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier
                .width(width)
                .fillMaxHeight()
                .padding(8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.Card)
                .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/**
 * The live tuning sheet: changes show on the page as they are made. Nothing is kept until "save for this site", and then it is
 * kept for that site (host and path) only.
 */
@Composable
fun TuningSheet(
    profile: DisplayProfile,
    saved: Boolean,
    onChange: (DisplayProfile) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier
                .widthIn(max = 360.dp)
                .fillMaxHeight()
                .padding(8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.Cream)
                .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.display_mode), fontSize = PanelText, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.display_mode_native), profile.mode == DisplayMode.NATIVE, Modifier.weight(1f)) { onChange(profile.copy(mode = DisplayMode.NATIVE)) }
                ModeChip(stringResource(R.string.display_mode_wide), profile.mode == DisplayMode.WIDE, Modifier.weight(1f)) { onChange(profile.copy(mode = DisplayMode.WIDE)) }
                ModeChip(stringResource(R.string.display_mode_portrait), profile.mode == DisplayMode.PORTRAIT_FIT, Modifier.weight(1.3f)) { onChange(profile.copy(mode = DisplayMode.PORTRAIT_FIT)) }
            }
            if (profile.mode != DisplayMode.NATIVE) {
                Text(stringResource(R.string.display_zoom), fontSize = PanelText, fontWeight = FontWeight.Bold, color = Palette.Ink)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    SheetButton("+", stringResource(R.string.display_zoom_in), Modifier.width(64.dp)) { onChange(profile.copy(zoom = (profile.zoom + ZOOM_STEP).coerceAtMost(DisplayProfile.MAX_ZOOM))) }
                    Text(stringResource(R.string.display_zoom_value, profile.zoom), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                    SheetButton("−", stringResource(R.string.display_zoom_out), Modifier.width(64.dp)) { onChange(profile.copy(zoom = (profile.zoom - ZOOM_STEP).coerceAtLeast(DisplayProfile.MIN_ZOOM))) }
                }
            }
            SheetButton(
                if (saved) "✓ " + stringResource(R.string.display_saved) else stringResource(R.string.display_save),
                null, Modifier.fillMaxWidth(), primary = !saved, onClick = onSave,
            )
            SheetButton(stringResource(R.string.display_reset), null, Modifier.fillMaxWidth(), onClick = onReset)
            SheetButton(stringResource(R.string.display_close), null, Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}

const val ZOOM_STEP = 5

@Composable
private fun ModeChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Palette.Ink else Palette.Tint)
            .clickable(onClick = onClick)
            .semantics { role = Role.RadioButton; this.selected = selected; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = PanelText, fontWeight = FontWeight.Medium, color = if (selected) Palette.Cream else Palette.Ink, textAlign = TextAlign.Center, modifier = Modifier.padding(4.dp))
    }
}

@Composable
private fun SheetButton(label: String, description: String?, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) Palette.Ink else Palette.Tint)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = description ?: label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = PanelText, fontWeight = FontWeight.Medium, color = if (primary) Palette.Cream else Palette.Ink, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** m:ss, or h:mm:ss after the first hour. */
fun formatElapsed(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
