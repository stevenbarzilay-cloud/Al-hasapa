package app.couch.alhasapa.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.ui.theme.Palette
import kotlin.math.roundToInt

/**
 * The TV's own settings (1.9.6), over whatever screen they were opened from, all with the remote: the arrows move between the buttons,
 * OK presses, Back closes.
 *  - the picture's size: when the TV crops the edges of the picture, it is made smaller until the four red corners show whole;
 *  - the size of the writing and the buttons;
 *  - the cursor's shape (a paw, a mouse, a fish, a ball of yarn, a plain arrow in the interface's colour) and its size.
 * Each change shows at once and is kept on this TV.
 */
@Composable
fun TvSettingsPanel(c: TvController) {
    Box(Modifier.fillMaxSize().background(Palette.Cream.copy(alpha = 0.97f)).padding(horizontal = 28.dp, vertical = 18.dp)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tvset_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Text(stringResource(R.string.tvset_body), fontSize = 17.sp, color = Palette.Ink2)
                }
                val close = stringResource(R.string.tv_info_close)
                Box(
                    Modifier.height(50.dp).widthIn(min = 130.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Ink)
                        .tvTarget(TvController.PANEL_CLOSE_ID, close) { c.closePanel() }.semantics { role = Role.Button; contentDescription = close },
                    contentAlignment = Alignment.Center,
                ) { Text(close, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Palette.Cream, modifier = Modifier.padding(horizontal = 18.dp)) }
            }

            Setting(stringResource(R.string.tvset_picture), stringResource(R.string.tvset_picture_help)) {
                Stepper(
                    "picture", percent(c.pictureScale), stringResource(R.string.tvset_smaller), stringResource(R.string.tvset_larger),
                    onLess = { c.pictureScale = step(c.pictureScale, -0.01f, MIN_PICTURE, 1f); c.onLookChanged() },
                    onMore = { c.pictureScale = step(c.pictureScale, +0.01f, MIN_PICTURE, 1f); c.onLookChanged() },
                )
            }
            Setting(stringResource(R.string.tvset_ui), stringResource(R.string.tvset_ui_help)) {
                Stepper(
                    "ui", percent(c.uiScale), stringResource(R.string.tvset_smaller), stringResource(R.string.tvset_larger),
                    onLess = { c.uiScale = step(c.uiScale, -0.05f, MIN_UI, MAX_UI); c.onLookChanged() },
                    onMore = { c.uiScale = step(c.uiScale, +0.05f, MIN_UI, MAX_UI); c.onLookChanged() },
                )
            }
            Setting(stringResource(R.string.tvset_cursor), stringResource(R.string.tvset_cursor_help)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (shape in CursorShape.entries) {
                        Choice(PANEL_PREFIX + "cursor-" + shape.id, shape.label, c.cursorShape == shape, Modifier.width(96.dp), onClick = { c.cursorShape = shape; c.onLookChanged() }) {
                            Canvas(Modifier.size(30.dp)) { with(CursorArt) { cursor(shape, Offset.Zero, size.width, c.accent, pressed = false) } }
                        }
                    }
                }
            }
            Setting(stringResource(R.string.tvset_cursor_size), null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in CursorSize.entries) {
                        Choice(PANEL_PREFIX + "size-" + s.id, s.label, c.cursorSize == s, Modifier.width(96.dp), onClick = { c.cursorSize = s; c.onLookChanged() }) {}
                    }
                }
            }
        }
    }
}

private fun percent(v: Float) = "${(v * 100).roundToInt()}%"

/** [v] moved by [d], kept between [lo] and [hi], to whole hundredths (so 0.96 stays 0.96 and not 0.95999). */
internal fun step(v: Float, d: Float, lo: Float, hi: Float): Float = (((v + d).coerceIn(lo, hi)) * 100f).roundToInt() / 100f

/** A setting: its name and a word about it at the right, its controls at the left. */
@Composable
private fun Setting(title: String, help: String?, controls: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Card).border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            if (help != null) Text(help, fontSize = 15.sp, color = Palette.Ink2)
        }
        // Left and right are the screen's own here: smaller at the left, larger at the right, as on any scale.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { controls() }
    }
}

@Composable
private fun Stepper(key: String, value: String, less: String, more: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Key(PANEL_PREFIX + key + "-smaller", "−", less, onLess)
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.width(76.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Key(PANEL_PREFIX + key + "-larger", "+", more, onMore)
    }
}

@Composable
private fun Key(id: String, glyph: String, label: String, onClick: () -> Unit) {
    val tv = LocalTv.current
    val lit = tv?.isFocused(id) == true
    Box(
        Modifier.size(56.dp, 50.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Tint).tvTarget(id, label, onClick)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (lit) Palette.Cream else Palette.Ink) }
}

/** One of a few choices: the chosen one wears a dark frame and a tick. */
@Composable
private fun Choice(id: String, label: String, chosen: Boolean, modifier: Modifier, onClick: () -> Unit, picture: @Composable () -> Unit) {
    val tv = LocalTv.current
    val lit = tv?.isFocused(id) == true
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.heightIn(min = 50.dp).clip(shape).background(Palette.Tint).tvTarget(id, label, onClick)
            .border(BorderStroke(if (chosen) 3.dp else 1.dp, if (chosen) Palette.Ink else Palette.Line2), shape)
            .semantics(mergeDescendants = true) { role = Role.RadioButton; selected = chosen; contentDescription = label }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        picture()
        Text((if (chosen) "✓ " else "") + label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (lit) Palette.Cream else Palette.Ink, maxLines = 1)
    }
}

/** For the tests: the cursor's colour when nothing set it. */
internal val DefaultAccent: Color = Palette.Red
