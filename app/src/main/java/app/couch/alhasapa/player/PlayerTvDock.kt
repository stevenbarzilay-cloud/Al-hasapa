package app.couch.alhasapa.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.tv.DockButton
import app.couch.alhasapa.tv.InfoButton
import app.couch.alhasapa.tv.ShapeIcon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import app.couch.alhasapa.tv.KeyboardButton
import app.couch.alhasapa.tv.PawButton
import app.couch.alhasapa.tv.TvController
import app.couch.alhasapa.ui.PieceIcon
import app.couch.alhasapa.ui.theme.Palette

/**
 * The game's frame on a TV, in the dock at the left edge: the time of day with the logo, the evening's score and the two of us, then
 * every control a game needs one press away (zoom, phone-shaped or wide, the frame itself and putting it away, the paw, the keyboard,
 * the TV's settings, the menu, back, the result and "we played"). The game keeps the whole middle. Narrow ("slim") it keeps only the
 * controls, as icons.
 */
@Composable
fun ColumnScope.PlayerTvDock(slim: Boolean, tv: TvController, data: ChromeData, actions: ChromeActions) {
    val lit = Palette.Cream
    val ink = Palette.Ink
    val h = if (slim) 48.dp else 52.dp
    if (!slim) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(44.dp))
            // The time of day, read again here when the minute changes: only these few characters are drawn again, not the game's screen.
            val clock by produceState(Clock.now()) { while (true) { delay(Clock.untilNextMinute()); value = Clock.now() } }
            val said = stringResource(R.string.clock_desc, clock)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(clock, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ink, modifier = Modifier.semantics { contentDescription = said })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val d = stringResource(R.string.panel_progress_full, data.info.done, data.info.total)
            Text("${data.info.done}/${data.info.total}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Red, modifier = Modifier.semantics { contentDescription = d })
            Text(stringResource(R.string.panel_points_short, data.info.points), fontSize = 20.sp, color = ink)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((style, name, mine) in listOf(Triple(data.info.pieceA, data.info.nameA, "a"), Triple(data.info.pieceB, data.info.nameB, "b"))) {
                val playing = data.info.who == mine
                PieceIcon(
                    style, 34.dp,
                    Modifier.then(if (playing) Modifier.border(2.dp, ink, CircleShape).clip(CircleShape) else Modifier),
                    label = if (playing) "$name, ${stringResource(R.string.panel_playing)}" else name,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }

    // Zoom: the one thing most often wanted in the middle of a game.
    if (data.zoom != null) {
        val out = stringResource(R.string.display_zoom_out)
        val inn = stringResource(R.string.display_zoom_in)
        if (slim) {
            DockButton(inn, Modifier.width(64.dp), height = h, onClick = { actions.onZoom(+1) }) { l -> Text("+", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (l) lit else ink) }
            DockButton(out, Modifier.width(64.dp), height = h, onClick = { actions.onZoom(-1) }) { l -> Text("−", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (l) lit else ink) }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DockButton(out, Modifier.weight(1f), onClick = { actions.onZoom(-1) }) { l -> Text("−", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (l) lit else ink) }
                Text(stringResource(R.string.display_zoom_value, data.zoom), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = ink)
                DockButton(inn, Modifier.weight(1f), onClick = { actions.onZoom(+1) }) { l -> Text("+", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (l) lit else ink) }
            }
        }
    }

    // Phone-shaped or wide, the frame (full or slim), and putting the frame away (the whole screen for the game).
    val mode = stringResource(R.string.bar_mode_desc)
    val frame = stringResource(if (slim) R.string.tv_frame_full else R.string.tv_frame_slim)
    val hide = stringResource(R.string.tv_frame_hide)
    val settings = stringResource(R.string.tv_settings_open)
    val threeUp: @Composable (@Composable () -> Unit, @Composable () -> Unit, @Composable () -> Unit) -> Unit = { a, b, c ->
        if (slim) { a(); b(); c() } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { a(); b(); c() }
    }
    val modeButton = @Composable {
        if (data.zoom != null) DockButton(mode, if (slim) Modifier.width(64.dp) else Modifier.weight(1f), height = h, onClick = actions.onToggleMode) { l ->
            ShapeIcon(portrait = data.portrait, color = if (l) lit else ink)
        }
    }
    val frameButton = @Composable {
        DockButton(frame, if (slim) Modifier.width(64.dp) else Modifier.weight(1f), height = h, onClick = { tv.toggleSlim() }) { l ->
            Text(if (slim) "◧" else "▣", fontSize = 28.sp, color = if (l) lit else ink)
        }
    }
    val hideButton = @Composable {
        DockButton(hide, if (slim) Modifier.width(64.dp) else Modifier.weight(1f), height = h, onClick = actions.onHideFrame) { l -> CornersIcon(if (l) lit else ink) }
    }
    threeUp(modeButton, frameButton, hideButton)

    // The paw, the keyboard, and the TV's settings (the cursor's shape and size, the picture's size).
    val settingsButton = @Composable {
        DockButton(settings, if (slim) Modifier.width(64.dp) else Modifier.weight(1f), height = h, onClick = { tv.openPanel(app.couch.alhasapa.tv.TvPanel.SETTINGS) }) { l ->
            SlidersIcon(if (l) lit else ink)
        }
    }
    if (slim) { PawButton(tv, height = h); KeyboardButton(tv, height = h); settingsButton() } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PawButton(tv, Modifier.weight(1f)); KeyboardButton(tv, Modifier.weight(1f)); settingsButton()
        }
    }

    // The menu and back.
    val menu = stringResource(R.string.menu_open)
    val back = stringResource(R.string.player_back)
    val menuButton = @Composable { DockButton(menu, if (slim) Modifier.width(64.dp) else Modifier.weight(1f), height = h, onClick = actions.onMenu) { l -> Text("☰", fontSize = 28.sp, color = if (l) lit else ink) } }
    val backButton = @Composable { DockButton(back, if (slim) Modifier.width(64.dp) else Modifier.weight(1f), height = h, onClick = actions.onBack) { l -> Text("↩", fontSize = 28.sp, color = if (l) lit else ink) } }
    // The information button sits with the menu and back in the full frame (a row of three), so every control fits a TV's height.
    if (slim) { menuButton(); backButton() } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        menuButton(); backButton(); InfoButton(tv, Modifier.weight(1f), height = h)
    }

    // The result and "we played", with words (the two things a person does at the end of a game).
    if (slim) InfoButton(tv)
    if (!slim) {
        actions.onResult?.let { onResult ->
            val label = stringResource(R.string.player_result)
            DockButton(label, Modifier.fillMaxWidth(), onClick = onResult) { l -> Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (l) lit else ink) }
        }
        if (data.showPlayed) {
            val label = stringResource(R.string.player_played)
            DockButton(
                if (data.played) "✓ $label" else label, Modifier.fillMaxWidth(), selected = data.played, onClick = actions.onTogglePlayed,
            ) { l -> Text(if (data.played) "✓ $label" else label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (l || data.played) lit else ink) }
        }
    }
}

/** The four corners of a screen: "the whole screen for the game". Drawn, so it never shows as a missing letter. */
@Composable
private fun CornersIcon(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
        val s = size.minDimension; val a = s * 0.34f; val w = s * 0.11f
        for ((x, y) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
            val cx = x * s; val cy = y * s; val dx = if (x == 0f) 1f else -1f; val dy = if (y == 0f) 1f else -1f
            drawLine(color, androidx.compose.ui.geometry.Offset(cx, cy + dy * w / 2), androidx.compose.ui.geometry.Offset(cx + dx * a, cy + dy * w / 2), strokeWidth = w)
            drawLine(color, androidx.compose.ui.geometry.Offset(cx + dx * w / 2, cy), androidx.compose.ui.geometry.Offset(cx + dx * w / 2, cy + dy * a), strokeWidth = w)
        }
    }
}

/** Three sliders: "settings". */
@Composable
private fun SlidersIcon(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
        val s = size.minDimension; val w = s * 0.09f
        for ((i, k) in listOf(0.3f, 0.7f, 0.45f).withIndex()) {
            val y = s * (0.2f + i * 0.3f)
            drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(s, y), strokeWidth = w)
            drawCircle(color, radius = s * 0.13f, center = androidx.compose.ui.geometry.Offset(s * k, y))
        }
    }
}
