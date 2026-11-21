package app.couch.alhasapa.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.tv.tvTarget
import app.couch.alhasapa.display.UiSize
import app.couch.alhasapa.ui.PieceIcon
import app.couch.alhasapa.ui.theme.Palette

/** What the always-visible UI shows. The bars drop items from the least important end as the room gets short. */
enum class BarItem { MENU, BACK, TITLE, TIMER, PROGRESS, POINTS, PIECES, ZOOM, MODE, UI, RESULT, PLAYED }

/** What the bars and panels display. */
data class ChromeData(
    val title: String,
    val elapsed: String,
    val info: PlayerInfo,
    val played: Boolean,
    val showPlayed: Boolean,
    /** The zoom in percent, or null where zoom does not apply (a page shown as it is). */
    val zoom: Int?,
    /** The page is shown phone-shaped (otherwise wide), for the button that swaps them. */
    val portrait: Boolean = true,
    /** When the game began (System.currentTimeMillis), for a clock that ticks by itself; 0 = use [elapsed] as it is. */
    val startedAt: Long = 0L,
)

/** What they can do. [onResult] is null where there is no result to record (the sign-in screens). */
class ChromeActions(
    val onMenu: () -> Unit,
    val onBack: () -> Unit,
    val onResult: (() -> Unit)?,
    val onTogglePlayed: () -> Unit,
    val onZoom: (direction: Int) -> Unit,
    /** Swap between the phone-shaped and the wide view of the game. */
    val onToggleMode: () -> Unit = {},
    /** Move the always-visible UI to its next place (sides, top, bottom, both, a button). */
    val onCycleUi: () -> Unit = {},
)

/** Items in order of how much a bar misses them, most important first (the menu is never dropped). */
private val IMPORTANCE = listOf(
    BarItem.MENU, BarItem.BACK, BarItem.PLAYED, BarItem.RESULT, BarItem.TITLE, BarItem.TIMER, BarItem.ZOOM,
    BarItem.MODE, BarItem.UI, BarItem.PROGRESS, BarItem.POINTS, BarItem.PIECES,
)

/** About how wide each item is at the medium size, in dp. */
private fun baseWidth(item: BarItem): Float = when (item) {
    BarItem.MENU -> 56f; BarItem.BACK -> 92f; BarItem.TITLE -> 110f; BarItem.TIMER -> 84f; BarItem.PROGRESS -> 104f
    BarItem.POINTS -> 150f; BarItem.PIECES -> 200f; BarItem.ZOOM -> 176f; BarItem.RESULT -> 100f; BarItem.PLAYED -> 124f
    BarItem.MODE -> 112f; BarItem.UI -> 104f
}

private fun sizeFactor(size: UiSize) = when (size) { UiSize.SMALL -> 0.9f; UiSize.MEDIUM -> 1f; UiSize.LARGE -> 1.25f }

/** Text size on the bars: never under 20 sp, bigger on the large size. */
fun barTextSp(size: UiSize) = when (size) { UiSize.SMALL -> 20; UiSize.MEDIUM -> 22; UiSize.LARGE -> 26 }

/** The wanted items that fit in [widthDp], keeping the most important first (and the wanted order for display). */
fun fitItems(wanted: List<BarItem>, widthDp: Float, size: UiSize): List<BarItem> {
    val f = sizeFactor(size)
    var used = 16f
    val keep = HashSet<BarItem>()
    for (item in IMPORTANCE) {
        if (item !in wanted) continue
        val w = baseWidth(item) * f + 8f
        if (item == BarItem.MENU || used + w <= widthDp) { keep += item; used += w }
    }
    return wanted.filter { it in keep }
}

/**
 * A persistent bar of the couch UI, at the top or the bottom. Always on screen: the menu button is its first item and is never
 * dropped. Right to left inside, like the rest of the app.
 */
@Composable
fun PlayerBar(
    wanted: List<BarItem>,
    size: UiSize,
    data: ChromeData,
    actions: ChromeActions,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BoxWithConstraints(
            modifier
                .fillMaxWidth()
                .height(size.barDp.dp)
                .background(Palette.Card)
                .border(BorderStroke(1.dp, Palette.Line2)),
        ) {
            val shown = fitItems(wanted, maxWidth.value, size)
            val sp = barTextSp(size)
            val h = maxOf(48f, size.barDp - 8f).dp          // never under the 48 dp touch minimum, even on the smallest bar
            Row(
                Modifier.fillMaxWidth().height(size.barDp.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (item in shown) when (item) {
                    BarItem.MENU -> MenuButton(h, actions.onMenu)
                    BarItem.BACK -> BarButton(stringResource(R.string.player_back), h, sp, actions.onBack)
                    BarItem.TITLE -> Text(
                        data.title, Modifier.weight(1f), fontSize = (sp + 2).sp, fontWeight = FontWeight.Bold, color = Palette.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    BarItem.TIMER -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(data.elapsed, fontSize = sp.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.semantics { contentDescription = data.elapsed })
                    }
                    BarItem.PROGRESS -> {
                        val d = stringResource(R.string.panel_progress_full, data.info.done, data.info.total)
                        Text("${data.info.done}/${data.info.total}", fontSize = sp.sp, fontWeight = FontWeight.Bold, color = Palette.Red, modifier = Modifier.semantics { contentDescription = d })
                    }
                    BarItem.POINTS -> Text(stringResource(R.string.panel_points, data.info.points), fontSize = sp.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1)
                    BarItem.PIECES -> {
                        BarPerson(data.info.pieceA, data.info.nameA, data.info.who == "a", h, sp)
                        BarPerson(data.info.pieceB, data.info.nameB, data.info.who == "b", h, sp)
                    }
                    BarItem.ZOOM -> data.zoom?.let { ZoomGroup(it, h, sp, actions.onZoom) }
                    BarItem.MODE -> if (data.zoom != null) {
                        val d = stringResource(R.string.bar_mode_desc)
                        BarButton(stringResource(if (data.portrait) R.string.bar_mode_portrait else R.string.bar_mode_wide), h, sp, actions.onToggleMode, description = d)
                    }
                    BarItem.UI -> BarButton(stringResource(R.string.bar_ui), h, sp, actions.onCycleUi, description = stringResource(R.string.bar_ui_desc))
                    BarItem.RESULT -> actions.onResult?.let { BarButton(stringResource(R.string.player_result), h, sp, it) }
                    BarItem.PLAYED -> if (data.showPlayed) {
                        val label = stringResource(R.string.player_played)
                        BarButton(if (data.played) "✓ $label" else label, h, sp, actions.onTogglePlayed, highlighted = data.played)
                    }
                }
                // With no title to stretch, the items still hug the two ends.
                if (BarItem.TITLE !in shown) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The hamburger: always one tap to every setting. */
@Composable
fun MenuButton(height: androidx.compose.ui.unit.Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.menu_open)
    Box(
        modifier
            .size(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Ink)
            .tvTarget(onClick)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text("☰", fontSize = 26.sp, color = Palette.Cream) }
}

/** The menu button on its own, floating in a corner, for the "minimal" UI. */
@Composable
fun FloatingMenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.menu_open)
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Palette.Card.copy(alpha = 0.92f))
            .border(BorderStroke(2.dp, Palette.Ink), CircleShape)
            .tvTarget(onClick)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text("☰", fontSize = 26.sp, color = Palette.Ink) }
}

@Composable
fun BarButton(label: String, height: androidx.compose.ui.unit.Dp, sp: Int, onClick: () -> Unit, highlighted: Boolean = false, description: String = label) {
    Box(
        Modifier
            .heightIn(min = height)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) Palette.Ink else Palette.Tint)
            .tvTarget(onClick)
            .padding(horizontal = 14.dp)
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (highlighted) Palette.Cream else Palette.Ink, fontSize = sp.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Zoom out, the zoom, zoom in: the quick way to make the game bigger. */
@Composable
fun ZoomGroup(zoom: Int, height: androidx.compose.ui.unit.Dp, sp: Int, onZoom: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BarButton("−", height, sp, { onZoom(-1) }, description = stringResource(R.string.display_zoom_out))
        Text(stringResource(R.string.display_zoom_value, zoom), fontSize = sp.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        BarButton("+", height, sp, { onZoom(+1) }, description = stringResource(R.string.display_zoom_in))
    }
}

@Composable
private fun BarPerson(style: app.couch.alhasapa.data.PieceStyle, name: String, active: Boolean, height: androidx.compose.ui.unit.Dp, sp: Int) {
    val playing = stringResource(R.string.panel_playing)
    Row(
        Modifier
            .heightIn(min = height)
            .clip(RoundedCornerShape(12.dp))
            .then(if (active) Modifier.border(BorderStroke(2.dp, Palette.Ink), RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = if (active) "$name, $playing" else name },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PieceIcon(style, 28.dp)
        Text(name, fontSize = sp.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = Palette.Ink, maxLines = 1)
    }
}
