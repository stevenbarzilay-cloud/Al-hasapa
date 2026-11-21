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
import androidx.compose.foundation.selection.toggleable
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
import app.couch.alhasapa.tv.tvTarget
import app.couch.alhasapa.display.DisplayMode
import app.couch.alhasapa.display.DisplayProfile
import app.couch.alhasapa.display.UiPlace
import app.couch.alhasapa.display.UiSize
import app.couch.alhasapa.ui.PieceIcon
import app.couch.alhasapa.ui.theme.Palette

/** Panels read from the couch: nothing in them is below 20sp. */
private val PanelText = 20.sp

/** The panel beside the game (the one on the right, where a Hebrew reader begins): whose couch, which game, how long. */
@Composable
fun IdentityPanel(
    width: Dp, title: String, elapsed: String, modifier: Modifier = Modifier, compact: Boolean = false,
    /** The menu is part of the panel, so the settings are never hidden: always one tap away. */
    onMenu: () -> Unit = {}, onBack: (() -> Unit)? = null, zoom: Int? = null, onZoom: (Int) -> Unit = {},
    portrait: Boolean = true, onMode: (() -> Unit)? = null, onUi: (() -> Unit)? = null,
) {
    PanelFrame(width, modifier, compact) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MenuButton(56.dp, onMenu)
            if (onBack != null && !compact) BarButton(stringResource(R.string.player_back), 56.dp, 20, onBack)
        }
        Spacer(Modifier.height(8.dp))
        // Narrow panels keep what matters while playing (the game and the clock) and drop the decoration.
        if (!compact) {
            Image(
                painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                modifier = Modifier.size(minOf(96.dp, width - 24.dp)),
            )
            Text(stringResource(R.string.panel_logo), fontSize = PanelText, color = Palette.Ink2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
        }
        Text(title, fontSize = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center, lineHeight = if (compact) 26.sp else 30.sp)
        Spacer(Modifier.height(12.dp))
        // Digits read left to right even in a Hebrew sentence, so the clock sits in its own left-to-right run.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(elapsed, fontSize = if (compact) 28.sp else 34.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.semantics { contentDescription = elapsed })
        }
        if (zoom != null) {
            Spacer(Modifier.height(12.dp))
            ZoomGroup(zoom, 56.dp, 20, onZoom)
        }
        // The two things worth changing in the middle of a game: phone-shaped or wide, and where this UI sits.
        if (!compact && (onMode != null || onUi != null)) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onMode != null && zoom != null) {
                    BarButton(stringResource(if (portrait) R.string.bar_mode_portrait else R.string.bar_mode_wide), 56.dp, 20, onMode, description = stringResource(R.string.bar_mode_desc))
                }
                if (onUi != null) BarButton(stringResource(R.string.bar_ui), 56.dp, 20, onUi, description = stringResource(R.string.bar_ui_desc))
            }
        }
    }
}

/** The panel on the other side: the evening so far and the two of us. */
@Composable
fun ProgressPanel(width: Dp, info: PlayerInfo, modifier: Modifier = Modifier, compact: Boolean = false, bottom: @Composable () -> Unit = {}) {
    PanelFrame(width, modifier, compact) {
        Text("${info.done}", fontSize = if (compact) 44.sp else 56.sp, fontWeight = FontWeight.Bold, color = Palette.Red, lineHeight = if (compact) 48.sp else 60.sp)
        Text(stringResource(R.string.panel_progress, info.total), fontSize = PanelText, color = Palette.Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.panel_points, info.points), fontSize = PanelText, fontWeight = FontWeight.SemiBold, color = Palette.Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        Person(info.pieceA, info.nameA, active = info.who == "a")
        Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
        Person(info.pieceB, info.nameB, active = info.who == "b")
        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        bottom()
    }
}

/** The big button at the foot of the progress panel. */
@Composable
fun PanelButton(label: String, primary: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (primary) Palette.Ink else Palette.Tint)
            .tvTarget(onClick)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (primary) Palette.Cream else Palette.Ink)
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
private fun PanelFrame(width: Dp, modifier: Modifier, compact: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // Both panels are right to left inside, whichever side of the screen they stand on.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier
                .width(width)
                .fillMaxHeight()
                .padding(if (compact) 4.dp else 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.Card)
                .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(if (compact) 8.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/**
 * The menu behind the hamburger: the quick actions (back, result, "we played") and the live tuning. Changes show on the page as they
 * are made. Nothing is kept until "save for this site", and then it is kept for that site (host and path) only.
 */
@Composable
fun TuningSheet(
    profile: DisplayProfile,
    saved: Boolean,
    /** What the page believes the screen is ("430×800"), once known. */
    pageSees: String?,
    onChange: (DisplayProfile) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** The picture is magnified past what the page can re-fit, so the view can be moved. */
    magnified: Boolean = false,
    onBack: () -> Unit = {},
    onResult: (() -> Unit)? = null,
    played: Boolean = false,
    showPlayed: Boolean = false,
    onTogglePlayed: () -> Unit = {},
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
                .padding(16.dp),
        ) {
          // The settings scroll; the three buttons that act on them stay pinned below, so saving is never a scroll away.
          Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.menu_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SheetButton(stringResource(R.string.player_back), null, Modifier.weight(1f), onClick = onBack)
                if (onResult != null) SheetButton(stringResource(R.string.player_result), null, Modifier.weight(1f), onClick = onResult)
            }
            if (showPlayed) {
                val label = stringResource(R.string.player_played)
                SheetButton(if (played) "✓ $label" else label, null, Modifier.fillMaxWidth(), primary = played, onClick = onTogglePlayed)
            }
            Text(stringResource(R.string.display_mode), fontSize = PanelText, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.display_mode_native), profile.mode == DisplayMode.NATIVE, Modifier.weight(1f)) { onChange(profile.copy(mode = DisplayMode.NATIVE)) }
                ModeChip(stringResource(R.string.display_mode_wide), profile.mode == DisplayMode.WIDE, Modifier.weight(1f)) { onChange(profile.copy(mode = DisplayMode.WIDE)) }
                ModeChip(stringResource(R.string.display_mode_portrait), profile.mode == DisplayMode.PORTRAIT_FIT, Modifier.weight(1.3f)) { onChange(profile.copy(mode = DisplayMode.PORTRAIT_FIT)) }
            }
            if (profile.mode != DisplayMode.NATIVE) {
                Text(stringResource(R.string.display_zoom), fontSize = PanelText, fontWeight = FontWeight.Bold, color = Palette.Ink)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    SheetButton("+", stringResource(R.string.display_zoom_in), Modifier.width(64.dp)) { onChange(profile.copy(zoom = zoomBy(profile.zoom, +1))) }
                    Text(stringResource(R.string.display_zoom_value, profile.zoom), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                    SheetButton("−", stringResource(R.string.display_zoom_out), Modifier.width(64.dp)) { onChange(profile.copy(zoom = zoomBy(profile.zoom, -1))) }
                }
                if (magnified) {
                    Text(stringResource(R.string.display_pan), fontSize = PanelText, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Text(stringResource(R.string.display_pan_hint), fontSize = PanelText, color = Palette.Ink2)
                    PanPad { dx, dy -> onChange(profile.copy(panX = (profile.panX + dx).coerceIn(0.0, 1.0), panY = (profile.panY + dy).coerceIn(0.0, 1.0))) }
                }
            }
            // The always-visible UI: where it sits and how much room it takes. It can be made small or just a button, never removed.
            Text(stringResource(R.string.menu_ui_place), fontSize = PanelText, fontWeight = FontWeight.Bold, color = Palette.Ink)
            val place = profile.place
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (profile.mode == DisplayMode.PORTRAIT_FIT) ModeChip(stringResource(R.string.place_sides), place == UiPlace.SIDES, Modifier.weight(1f)) { onChange(profile.copy(ui = UiPlace.SIDES)) }
                ModeChip(stringResource(R.string.place_top), place == UiPlace.TOP, Modifier.weight(1f)) { onChange(profile.copy(ui = UiPlace.TOP)) }
                ModeChip(stringResource(R.string.place_bottom), place == UiPlace.BOTTOM, Modifier.weight(1f)) { onChange(profile.copy(ui = UiPlace.BOTTOM)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.place_both), place == UiPlace.BOTH, Modifier.weight(1f)) { onChange(profile.copy(ui = UiPlace.BOTH)) }
                ModeChip(stringResource(R.string.place_minimal), place == UiPlace.MINIMAL, Modifier.weight(1f)) { onChange(profile.copy(ui = UiPlace.MINIMAL)) }
            }
            Text(stringResource(R.string.menu_ui_size), fontSize = PanelText, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.size_small), profile.size == UiSize.SMALL, Modifier.weight(1f)) { onChange(profile.copy(uiSize = UiSize.SMALL)) }
                ModeChip(stringResource(R.string.size_medium), profile.size == UiSize.MEDIUM, Modifier.weight(1f)) { onChange(profile.copy(uiSize = UiSize.MEDIUM)) }
                ModeChip(stringResource(R.string.size_large), profile.size == UiSize.LARGE, Modifier.weight(1f)) { onChange(profile.copy(uiSize = UiSize.LARGE)) }
            }
            Text(stringResource(R.string.display_margin), fontSize = PanelText, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in MARGINS) {
                    val label = stringResource(R.string.display_margin_value, if (m == m.toInt().toDouble()) m.toInt().toString() else m.toString())
                    ModeChip(label, profile.safeArea == m, Modifier.weight(1f)) { onChange(profile.copy(safeArea = m)) }
                }
            }
            if (pageSees != null) Text(stringResource(R.string.display_page_sees, pageSees), fontSize = PanelText, color = Palette.Ink2)
          }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SheetButton(
                    if (saved) "✓ " + stringResource(R.string.display_saved) else stringResource(R.string.display_save),
                    null, Modifier.weight(2f), primary = !saved, onClick = onSave,
                )
                SheetButton(stringResource(R.string.display_reset), null, Modifier.weight(1.4f), onClick = onReset)
                SheetButton(stringResource(R.string.display_close), null, Modifier.weight(1f), onClick = onClose)
            }
        }
    }
}

/** One press of a zoom button. */
const val ZOOM_STEP = 10

/** The TV safe-area choices: nothing, the usual, and a wide one for TVs that crop. */
private val MARGINS = listOf(0.0, 2.5, 5.0)

/** The zoom after one step up (+1) or down (-1), kept on the steps and inside the allowed range. */
fun zoomBy(zoom: Int, direction: Int): Int =
    ((zoom + direction * ZOOM_STEP) / ZOOM_STEP * ZOOM_STEP).coerceIn(DisplayProfile.MIN_ZOOM, DisplayProfile.MAX_ZOOM)

private const val PAN_STEP = 0.12

/** Four arrows that move the magnified view over the page. Left and right are the screen's own. */
@Composable
private fun PanPad(onPan: (dx: Double, dy: Double) -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SheetButton("▲", stringResource(R.string.display_pan_up), Modifier.width(64.dp)) { onPan(0.0, -PAN_STEP) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SheetButton("◀", stringResource(R.string.display_pan_left), Modifier.width(64.dp)) { onPan(-PAN_STEP, 0.0) }
                SheetButton("▼", stringResource(R.string.display_pan_down), Modifier.width(64.dp)) { onPan(0.0, PAN_STEP) }
                SheetButton("▶", stringResource(R.string.display_pan_right), Modifier.width(64.dp)) { onPan(PAN_STEP, 0.0) }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Palette.Ink else Palette.Tint)
            .tvTarget(onClick)
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
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) Palette.Ink else Palette.Tint)
            .tvTarget(onClick)
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
