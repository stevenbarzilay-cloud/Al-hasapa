package app.couch.alhasapa.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.remote.KeyClient
import app.couch.alhasapa.remote.KeyLink
import app.couch.alhasapa.tv.PawIcon
import app.couch.alhasapa.tv.drawPaw
import app.couch.alhasapa.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What keyboard mode sends (to the TV over the home network; a fake one in tests). */
interface KeySink {
    fun text(s: String)
    fun key(k: KeyLink.Key, a: KeyLink.Action = KeyLink.Action.TAP)
    fun code(c: String)
    fun retry()
    fun address(a: String)
    fun permission()

    /** The touchpad: [KeyLink.Msg.Pointer], [KeyLink.Msg.Move], [KeyLink.Msg.Click], [KeyLink.Msg.Drag], [KeyLink.Msg.Scroll]. */
    fun pointer(m: KeyLink.Msg) {}
}

/**
 * Keyboard mode, sideways: the whole phone is the TV app's keyboard (what is typed shows on the TV, not here). A thin strip on top holds
 * the switch back to playing on the phone and what the TV shows. At the right edge a cat's paw peeks in: pulled, its arm stretches and
 * the touchpad (a couch cushion) comes out with it, and the keyboard makes room.
 */
@Composable
fun KeyboardModeScreen(state: KeyClient.State, sink: KeySink, onGame: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.Cream).windowInsetsPadding(WindowInsets.displayCutout).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TopStrip(state, onGame)
        Box(Modifier.fillMaxWidth().weight(1f).padding(end = if (state is KeyClient.State.Connected) 0.dp else 12.dp)) {
            when (state) {
                is KeyClient.State.Connected -> Connected(sink)
                is KeyClient.State.NeedCode -> CodeEntry(state.left, sink)
                KeyClient.State.Looking -> Looking(stringResource(R.string.kb_looking))
                is KeyClient.State.Connecting -> Looking(stringResource(R.string.kb_connecting))
                is KeyClient.State.NotFound -> NotFound(state.reason, sink)
                KeyClient.State.Wait -> Message(stringResource(R.string.kb_wait)) { Pill(stringResource(R.string.kb_retry), dark = false) { sink.retry() } }
            }
        }
    }
}

@Composable
private fun TopStrip(state: KeyClient.State, onGame: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(42.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ModeSwitch(keys = true, onGame = onGame, modifier = Modifier.width(280.dp).height(40.dp))
        val (text, bold, live) = when (state) {
            is KeyClient.State.Connected -> Triple(stringResource(R.string.kb_connected) + " ·", state.screen.ifBlank { state.tv }, true)
            is KeyClient.State.NeedCode -> Triple(stringResource(R.string.kb_code_title), null, false)
            else -> Triple("", null, false)
        }
        if (text.isNotEmpty()) StatusLine(text, bold, live, Modifier.weight(1f))
    }
}

/** Playing here, or being the TV's keyboard: one switch, the same as in the hub's header. */
@Composable
fun ModeSwitch(keys: Boolean, onGame: () -> Unit, modifier: Modifier = Modifier, onKeys: () -> Unit = {}) {
    val desc = stringResource(R.string.kb_mode_switch)
    Row(
        modifier.clip(RoundedCornerShape(26.dp)).background(Palette.Tint)
            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(26.dp)).padding(3.dp).semantics { contentDescription = desc },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Segment(stringResource(R.string.kb_mode_game), selected = !keys, onClick = onGame)
        Segment(stringResource(R.string.kb_mode_keys), selected = keys, onClick = onKeys)
    }
}

@Composable
private fun RowScope.Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(if (selected) Palette.Ink else Palette.Tint)
            .clickable(onClick = onClick).semantics { role = Role.Tab; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Palette.Cream else Palette.Ink2, maxLines = 1) }
}

@Composable
private fun StatusLine(text: String, bold: String?, live: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PawIcon(22.dp, if (live) Palette.Red else Palette.Ink2, outline = Palette.Cream)
        Text(text, fontSize = 16.sp, color = Palette.Ink2, maxLines = 1)
        if (bold != null) Text(bold, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------------
// Connected: the keyboard, and the paw's touchpad pulled out from the right edge
// ---------------------------------------------------------------------------------------------------------------------------------------

/** The pull tab at the right edge (the paw is in it): flush with the edge when the touchpad is away, at the cushion's edge when it is out. */
private val TabWidth = 40.dp
private val TabHeight = 76.dp

@Composable
private fun Connected(sink: KeySink) {
    val scope = rememberCoroutineScope()
    val out = remember { Animatable(0f) }                       // 0: put away, 1: all the way out
    var open by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }           // the pad's finger, held down (a drag on the TV)
    val arm by animateFloatAsState(if (pulling) 1f else 0f, tween(if (pulling) 90 else 420), label = "arm")

    fun settle(toOpen: Boolean) {
        if (toOpen != open) {
            open = toOpen
            if (!toOpen && dragging) { dragging = false; sink.pointer(KeyLink.Msg.Drag(false)) }
            sink.pointer(KeyLink.Msg.Pointer(toOpen))
        }
        scope.launch { out.animateTo(if (toOpen) 1f else 0f, spring(dampingRatio = if (toOpen) 0.62f else 0.8f, stiffness = 380f)) }
    }
    DisposableEffect(Unit) { onDispose { if (open) sink.pointer(KeyLink.Msg.Pointer(false)) } }

    // Left and right are the screen's own here: the drawer comes from the right edge, the keys are where a keyboard has them.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            val density = LocalDensity.current
            val panel = (maxWidth * 0.42f).coerceIn(250.dp, 420.dp)
            val panelPx = with(density) { panel.toPx() }
            val widthPx = with(density) { maxWidth.toPx() }
            val tabPx = with(density) { TabWidth.toPx() }
            val p = out.value
            // The keyboard, the tab and the cushion side by side: the tab is always in the gap, never over a key.
            val keyboard = maxWidth - TabWidth - 6.dp - panel * p
            val tabLeft = widthPx - tabPx - panelPx * p
            val pawX = tabLeft + tabPx / 2
            val midY = with(density) { (maxHeight / 2).toPx() }

            PhoneKeyboard(sink, Modifier.align(Alignment.CenterStart).width(keyboard).fillMaxHeight())

            // The cushion: its left edge follows the paw. Not there at all while it is put away (nothing to read or to draw).
            if (p > 0f) Cushion(
                sink, dragging, onDragging = { dragging = it }, onClose = { settle(false) },
                modifier = Modifier.absoluteOffset { IntOffset((widthPx - panelPx * p).roundToInt(), 0) }.width(panel).fillMaxHeight(),
            )

            // While it is pulled, the cat's arm stretches from the edge of the screen to the paw.
            if (arm > 0f) Canvas(Modifier.matchParentSize()) {
                val h = 42.dp.toPx()
                val left = pawX
                val alpha = arm
                val top = midY - h / 2
                drawRoundRect(Palette.Cream.copy(alpha = alpha), topLeft = Offset(left, top), size = Size(size.width - left + h, h), cornerRadius = CornerRadius(h / 2))
                // a tabby's stripes along the arm, the longer it is stretched the more of them
                var x = left + h * 0.9f
                while (x < size.width - 4f) {
                    drawLine(Palette.Pink.copy(alpha = 0.9f * alpha), Offset(x, top + h * 0.16f), Offset(x + h * 0.18f, top + h * 0.84f), strokeWidth = h * 0.16f)
                    x += h * 0.75f
                }
                drawRoundRect(Palette.Ink.copy(alpha = alpha), topLeft = Offset(left, top), size = Size(size.width - left + h, h), cornerRadius = CornerRadius(h / 2), style = Stroke(2.dp.toPx()))
            }

            // The paw: peeking in at the edge (it wiggles now and then), or at the cushion's edge. Pull it either way, or touch it.
            val wiggle = rememberInfiniteTransition(label = "wiggle")
            val w by wiggle.animateFloat(
                0f, 0f,
                infiniteRepeatable(keyframes { durationMillis = 5_200; 0f at 0; 0f at 4_300; 1f at 4_480; -1f at 4_700; 0.6f at 4_880; 0f at 5_200 }, RepeatMode.Restart),
                label = "w",
            )
            val drag = rememberDraggableState { delta -> scope.launch { out.snapTo((out.value - delta / panelPx).coerceIn(0f, 1f)) } }
            val tab = stringResource(R.string.kb_pad_handle)
            val tabH = with(density) { TabHeight.toPx() }
            Box(
                Modifier.absoluteOffset { IntOffset(tabLeft.roundToInt(), (midY - tabH / 2).roundToInt()) }.size(TabWidth, TabHeight)
                    .graphicsLayer { scaleY = if (pulling) 1.06f else 1f }
                    .clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)).background(Palette.Ink)
                    .draggable(drag, Orientation.Horizontal, onDragStarted = { pulling = true }, onDragStopped = { v ->
                        pulling = false
                        settle(if (abs(v) > 500f) v < 0 else out.value > 0.33f)
                    })
                    .clickable { settle(!open) }
                    .semantics { role = Role.Button; contentDescription = tab },
                contentAlignment = Alignment.Center,
            ) {
                // Toes towards the keys; tucked away, it wiggles now and then to say it can be pulled.
                PawIcon(30.dp, Palette.Red, outline = Palette.Ink, modifier = Modifier.graphicsLayer { rotationZ = -90f + 14f * w * (1f - p) })
            }
        }
    }
}

/**
 * The touchpad, a couch cushion: one finger moves the TV's paw (faster swipes go further), a short touch taps where the paw is (a print
 * appears where the finger was), two fingers scroll the page under the paw. Under it, a tap and a drag that holds the finger down.
 */
@Composable
private fun Cushion(sink: KeySink, dragging: Boolean, onDragging: (Boolean) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prints = remember { mutableStateListOf<Pair<Offset, Animatable<Float, *>>>() }
    val hint = stringResource(R.string.kb_pad_hint)
    val draggingNow by rememberUpdatedState(dragging)
    Column(
        modifier.clip(RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp)).background(Palette.Card)
            .border(BorderStroke(1.5.dp, Palette.Line2), RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.kb_pad), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.weight(1f))
                val close = stringResource(R.string.kb_pad_close)
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Palette.Tint).clickable(onClick = onClose).semantics { role = Role.Button; contentDescription = close },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", fontSize = 16.sp, color = Palette.Ink) }
            }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)).background(Palette.Pink.copy(alpha = 0.5f))
                .drawBehind {
                    // the cushion's stitching
                    val inset = 7.dp.toPx()
                    drawRoundRect(
                        Palette.Ink.copy(alpha = 0.22f), topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                        cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    )
                }
                .semantics { contentDescription = hint }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        first.consume()
                        val start = first.uptimeMillis
                        var end = start
                        var travel = 0f
                        var fingers = 1
                        var dx = 0f; var dy = 0f; var scroll = 0f
                        var sentAt = start
                        fun flush(now: Long) {
                            if (dx != 0f || dy != 0f) {
                                val mx = dx / density; val my = dy / density
                                val speed = hypot(mx, my) / (now - sentAt).coerceAtLeast(1L)          // dp per ms
                                val gain = 1f + ((speed - 0.25f) * 2.2f).coerceIn(0f, 2.5f)
                                sink.pointer(KeyLink.Msg.Move(mx * gain, my * gain))
                                dx = 0f; dy = 0f
                            }
                            if (scroll != 0f) { sink.pointer(KeyLink.Msg.Scroll(scroll / density)); scroll = 0f }
                            sentAt = now
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val down = event.changes.filter { it.pressed }
                            end = event.changes.maxOf { it.uptimeMillis }
                            if (down.isEmpty()) break
                            fingers = maxOf(fingers, down.size)
                            if (down.size >= 2) {
                                scroll += down.map { it.position.y - it.previousPosition.y }.average().toFloat()
                            } else {
                                val d = down[0].position - down[0].previousPosition
                                travel += d.getDistance(); dx += d.x; dy += d.y
                            }
                            event.changes.forEach { it.consume() }
                            if (end - sentAt >= 16) flush(end)
                        }
                        flush(end)
                        if (fingers == 1 && travel < 8.dp.toPx() && end - start < 300) {
                            sink.pointer(KeyLink.Msg.Click)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            if (draggingNow) onDragging(false)
                            val a = Animatable(1f)
                            val print = first.position to a
                            prints += print
                            scope.launch { a.animateTo(0f, tween(650)); prints.remove(print) }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            PawIcon(72.dp, Palette.Ink.copy(alpha = 0.08f), outline = Palette.Pink.copy(alpha = 0f))
            Canvas(Modifier.matchParentSize()) {
                for ((at, a) in prints) drawPaw(at, density * 0.55f, Palette.Red.copy(alpha = a.value), Palette.Cream.copy(alpha = a.value))
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Text(hint, fontSize = 13.sp, color = Palette.Ink2, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton(stringResource(R.string.kb_pad_click), on = false, Modifier.weight(1f)) {
                    onDragging(false)
                    sink.pointer(KeyLink.Msg.Click); view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                PadButton(stringResource(if (dragging) R.string.kb_pad_drag_on else R.string.kb_pad_drag), on = dragging, Modifier.weight(1f)) {
                    onDragging(!dragging)
                    sink.pointer(KeyLink.Msg.Drag(!dragging))
                }
            }
        }
    }
}

@Composable
private fun PadButton(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(46.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Palette.Red else Palette.Ink).clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Cream, maxLines = 1) }
}

// ---------------------------------------------------------------------------------------------------------------------------------------
// The keyboard
// ---------------------------------------------------------------------------------------------------------------------------------------

/** Width of the column beside the letters (Backspace, the paw's Enter, the arrows), in key units. */
private const val SIDE_UNITS = 2.9f

/**
 * The keyboard: a tray of soft keys in the app's colours. A key sends the moment the finger lands (fast typing), and goes red under it.
 * Shift is one capital, twice is all capitals. Backspace repeats while held; an arrow held is a key held on the TV.
 */
@Composable
private fun PhoneKeyboard(sink: KeySink, modifier: Modifier) {
    var layout by rememberSaveable { mutableStateOf(PhoneLayout.EN) }
    var shift by remember { mutableStateOf(false) }
    var caps by remember { mutableStateOf(false) }
    var enters by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(22.dp)).background(Palette.Tint).border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(22.dp)).padding(6.dp),
    ) {
        val unit = maxWidth.value / (PhoneKeys.ROW_UNITS + SIDE_UNITS + 2.2f)
        val font = (unit * 0.46f).coerceIn(13f, 25f)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(PhoneKeys.ROW_UNITS).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in PhoneKeys.rows(layout)) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for ((key, w) in row) {
                            when (key) {
                                PKey.Gap -> Spacer(Modifier.weight(w))
                                else -> KeyCap(key, w, font, shift = shift || caps, lit = key == PKey.Shift && (shift || caps), locked = key == PKey.Shift && caps) {
                                    tick()
                                    when (key) {
                                        PKey.Shift -> when { caps -> { caps = false; shift = false }; shift -> { caps = true; shift = false }; else -> shift = true }
                                        is PKey.Switch -> { layout = key.to; shift = false; caps = false }
                                        else -> {
                                            PhoneKeys.message(key, shift || caps)?.let { m ->
                                                when (m) {
                                                    is KeyLink.Msg.Text -> sink.text(m.s)
                                                    is KeyLink.Msg.Press -> sink.key(m.key, m.action)
                                                    else -> {}
                                                }
                                            }
                                            if (shift && !caps && key is PKey.Ch) shift = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(SIDE_UNITS).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RepeatKey("⌫", stringResource(R.string.kb_backspace), font, Modifier.weight(1f).fillMaxWidth()) { tick(); sink.key(KeyLink.Key.BACKSPACE) }
                PawEnter(enters, Modifier.weight(2f).fillMaxWidth()) { tick(); sink.key(KeyLink.Key.ENTER); enters++ }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    HoldKey("▲", stringResource(R.string.kb_up), KeyLink.Key.UP, sink, font, Modifier.fillMaxHeight().fillMaxWidth(0.34f)) { tick() }
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    HoldKey("◀", stringResource(R.string.kb_left), KeyLink.Key.LEFT, sink, font, Modifier.weight(1f).fillMaxHeight()) { tick() }
                    HoldKey("▼", stringResource(R.string.kb_down), KeyLink.Key.DOWN, sink, font, Modifier.weight(1f).fillMaxHeight()) { tick() }
                    HoldKey("▶", stringResource(R.string.kb_right), KeyLink.Key.RIGHT, sink, font, Modifier.weight(1f).fillMaxHeight()) { tick() }
                }
            }
        }
    }
}

@Composable
private fun RowScope.KeyCap(key: PKey, weight: Float, font: Float, shift: Boolean, lit: Boolean, locked: Boolean, onPress: () -> Unit) {
    val press by rememberUpdatedState(onPress)
    var down by remember { mutableStateOf(false) }
    val (label, size, description) = when (key) {
        is PKey.Ch -> Triple(if (shift) key.upper else key.lower, font, if (shift) key.upper else key.lower)
        PKey.Shift -> Triple("⇧", font, "Shift")
        PKey.Esc -> Triple("Esc", font * 0.62f, "Esc")
        PKey.Tab -> Triple("Tab", font * 0.62f, "Tab")
        PKey.Space -> Triple("", font, stringResource(R.string.kb_space))
        is PKey.Switch -> Triple(key.label, font * 0.66f, key.label)
        PKey.Gap -> Triple("", font, "")
    }
    val special = key !is PKey.Ch && key != PKey.Space
    Box(
        Modifier.weight(weight).fillMaxHeight().offset { IntOffset(0, if (down) 2.dp.roundToPx() else 0) }
            .clip(RoundedCornerShape(11.dp))
            .background(when { down -> Palette.Red; lit -> Palette.Ink; special -> Palette.Cream; else -> Palette.Card })
            .border(BorderStroke(1.dp, if (down || lit) Palette.Ink.copy(alpha = 0f) else Palette.Line2), RoundedCornerShape(11.dp))
            .pointerInput(key) { detectTapGestures(onPress = { down = true; press(); tryAwaitRelease(); down = false }) }
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (key == PKey.Space) PawIcon(22.dp, if (down) Palette.Cream else Palette.Ink.copy(alpha = 0.18f), outline = Palette.Card.copy(alpha = 0f))
        else Text(
            label, fontSize = size.sp, fontWeight = if (special) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
            color = when { down || lit -> Palette.Cream; special -> Palette.Ink2; else -> Palette.Ink },
        )
        if (locked) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).width(18.dp).height(2.dp).background(Palette.Cream))
    }
}

/** Backspace: once at once, then again and again while it is held. */
@Composable
private fun RepeatKey(label: String, description: String, font: Float, modifier: Modifier, onPress: () -> Unit) {
    val press by rememberUpdatedState(onPress)
    var down by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(
        modifier.offset { IntOffset(0, if (down) 2.dp.roundToPx() else 0) }.clip(RoundedCornerShape(11.dp)).background(if (down) Palette.Red else Palette.Cream)
            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(11.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    down = true; press()
                    val again = scope.launch { delay(420); while (true) { press(); delay(55) } }
                    tryAwaitRelease()
                    again.cancel(); down = false
                })
            }
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = font.sp, color = if (down) Palette.Cream else Palette.Ink) }
}

/** An arrow: down on the TV while the finger is down, up when it lifts (a game that moves while an arrow is held sees it held). */
@Composable
private fun HoldKey(label: String, description: String, key: KeyLink.Key, sink: KeySink, font: Float, modifier: Modifier, onPress: () -> Unit) {
    val press by rememberUpdatedState(onPress)
    var down by remember { mutableStateOf(false) }
    Box(
        modifier.offset { IntOffset(0, if (down) 2.dp.roundToPx() else 0) }.clip(RoundedCornerShape(11.dp)).background(if (down) Palette.Red else Palette.Card)
            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(11.dp))
            .pointerInput(key) {
                detectTapGestures(onPress = {
                    down = true; press(); sink.key(key, KeyLink.Action.DOWN)
                    tryAwaitRelease()
                    sink.key(key, KeyLink.Action.UP); down = false
                })
            }
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = (font * 0.8f).sp, color = if (down) Palette.Cream else Palette.Ink) }
}

/** The tall Enter, with a cat's paw resting on it: each Enter the paw comes down on the key, which sinks into its red shadow. */
@Composable
private fun PawEnter(presses: Int, modifier: Modifier, onPress: () -> Unit) {
    val press by rememberUpdatedState(onPress)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(presses) {
        if (presses > 0) {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(90, easing = FastOutLinearInEasing))
            anim.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 420f))
        }
    }
    val p = anim.value
    val description = stringResource(R.string.kb_enter_paw)
    BoxWithConstraints(modifier) {
        Box(
            Modifier.fillMaxSize().absoluteOffset { IntOffset((4.dp.toPx() * (1 - p)).roundToInt(), (4.dp.toPx() * (1 - p)).roundToInt()) }
                .clip(RoundedCornerShape(14.dp)).background(Palette.Red),
        )
        Box(
            Modifier.fillMaxSize().absoluteOffset { IntOffset(0, (3.dp.toPx() * p).roundToInt()) }.clip(RoundedCornerShape(14.dp)).background(Palette.Ink)
                .pointerInput(Unit) { detectTapGestures(onPress = { press() }) }
                .semantics { role = Role.Button; contentDescription = description },
            contentAlignment = Alignment.BottomCenter,
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text("Enter ⏎", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Cream, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
        val pawSize: Dp = minOf(maxWidth * 0.5f, maxHeight * 0.45f, 58.dp)
        PawIcon(
            pawSize, Palette.Red, outline = Palette.Ink,
            modifier = Modifier.align(Alignment.TopCenter)
                .offset { IntOffset(0, (6.dp.toPx() + 16.dp.toPx() * p).roundToInt()) }
                .graphicsLayer { rotationZ = -12f + 12f * p; scaleY = 1f - 0.15f * p; scaleX = 1f + 0.06f * p },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------------
// Before it is connected
// ---------------------------------------------------------------------------------------------------------------------------------------

/** Looking for the TV: three paw prints walking. */
@Composable
private fun Looking(text: String) {
    val t = rememberInfiniteTransition(label = "walk")
    val step by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1_500, easing = LinearEasing), RepeatMode.Restart), label = "step")
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (i in 0 until 3) {
                val on = step >= i && step < i + 1.6f
                PawIcon(34.dp, if (on) Palette.Red else Palette.Line2, outline = Palette.Cream, modifier = Modifier.offset(y = if (i % 2 == 0) 0.dp else (-10).dp))
            }
        }
        Text(text, fontSize = 20.sp, color = Palette.Ink2, textAlign = TextAlign.Center)
    }
}

/** The first time with this TV: the four digits it shows, typed on a keypad of our own. */
@Composable
private fun CodeEntry(left: Int?, sink: KeySink) {
    var code by remember { mutableStateOf("") }
    LaunchedEffect(left) { if (left != null) code = "" }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.kb_code_body), fontSize = 18.sp, color = Palette.Ink2, textAlign = TextAlign.Center)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics { contentDescription = "קוד" }) {
                    for (i in 0 until 4) {
                        Box(
                            Modifier.size(56.dp, 66.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Card)
                                .border(BorderStroke(2.dp, if (i == code.length) Palette.Red else Palette.Line2), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text(code.getOrNull(i)?.toString().orEmpty(), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Ink) }
                    }
                }
            }
            if (left != null) Text(stringResource(R.string.kb_code_wrong, left), fontSize = 17.sp, color = Palette.Red, fontWeight = FontWeight.SemiBold)
        }
        NumPad(extra = emptyList(), modifier = Modifier.weight(1f).fillMaxHeight(), onKey = { k ->
            when (k) {
                "⌫" -> code = code.dropLast(1)
                else -> if (code.length < 4) { code += k; if (code.length == 4) sink.code(code) }
            }
        })
    }
}

/** Digits (and a few more keys) on soft keys, for the code and the TV's address. */
@Composable
private fun NumPad(extra: List<String>, modifier: Modifier, onKey: (String) -> Unit) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), extra.take(1) + listOf("0", "⌫"))
    val view = LocalView.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(22.dp)).background(Palette.Tint).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in rows) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.size < 3) Spacer(Modifier.weight(1f))
                for (k in row) {
                    var down by remember { mutableStateOf(false) }
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(if (down) Palette.Red else Palette.Card)
                            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(11.dp))
                            .pointerInput(k) { detectTapGestures(onPress = { down = true; view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onKey(k); tryAwaitRelease(); down = false }) }
                            .semantics { role = Role.Button; contentDescription = k },
                        contentAlignment = Alignment.Center,
                    ) { Text(k, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = if (down) Palette.Cream else Palette.Ink) }
                }
            }
        }
    }
}

@Composable
private fun NotFound(reason: String, sink: KeySink) {
    if (reason == KeyClient.NO_PERMISSION) {
        Message(stringResource(R.string.kb_permission)) { Pill(stringResource(R.string.kb_permission_button), dark = true) { sink.permission() } }
        return
    }
    var typing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.kb_notfound_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            for (line in listOf(stringResource(R.string.kb_notfound_1), stringResource(R.string.kb_notfound_2))) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PawIcon(18.dp, Palette.Red, outline = Palette.Cream)
                    Text(line, fontSize = 18.sp, color = Palette.Ink)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(stringResource(R.string.kb_retry), dark = true) { sink.retry() }
                Pill(stringResource(R.string.kb_address), dark = false) { typing = true }
            }
            if (typing) {
                Text(stringResource(R.string.kb_address_hint), fontSize = 15.sp, color = Palette.Ink2)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Card)
                            .border(BorderStroke(1.5.dp, Palette.Line2), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text(address.ifEmpty { "192.168.1.20" }, fontSize = 24.sp, color = if (address.isEmpty()) Palette.Line2 else Palette.Ink) }
                }
                Pill(stringResource(R.string.kb_connect), dark = true) { sink.address(address) }
            }
        }
        if (typing) NumPad(extra = listOf("."), modifier = Modifier.weight(1f).fillMaxHeight(), onKey = { k ->
            address = if (k == "⌫") address.dropLast(1) else (address + k).take(21)
        })
    }
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        PawIcon(40.dp, Palette.Red, outline = Palette.Cream)
        Text(text, fontSize = 19.sp, color = Palette.Ink, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 640.dp))
        action()
    }
}

@Composable
private fun Pill(label: String, dark: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(if (dark) Palette.Ink else Palette.Tint)
            .border(BorderStroke(1.dp, if (dark) Palette.Ink else Palette.Line2), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 20.dp).semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (dark) Palette.Cream else Palette.Ink) }
}
