package app.couch.alhasapa.keyboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.maxLengthReject
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.then
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.couch.alhasapa.R
import app.couch.alhasapa.remote.CaptureView
import app.couch.alhasapa.remote.KeyClient
import app.couch.alhasapa.remote.KeyLink
import app.couch.alhasapa.tv.PawIcon
import app.couch.alhasapa.ui.theme.Palette
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
 * Keyboard mode: the phone as the TV app's keyboard. The phone's own keyboard types (everything goes to the TV as it is typed), with the
 * keys a game wants that a phone keyboard lacks (Esc, Tab, arrows that can be held) and a big Enter that a cat's paw presses. At the top,
 * the switch back to playing on the phone.
 */
@Composable
fun KeyboardModeScreen(state: KeyClient.State, sink: KeySink, onGame: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Cream).systemBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ModeSwitch(keys = true, onGame = onGame)
            when (state) {
                is KeyClient.State.Connected -> Typing(state, sink)
                is KeyClient.State.NeedCode -> CodeEntry(state.left, sink)
                KeyClient.State.Looking -> Looking(stringResource(R.string.kb_looking))
                is KeyClient.State.Connecting -> Looking(stringResource(R.string.kb_connecting))
                is KeyClient.State.NotFound -> NotFound(state.reason, sink)
                KeyClient.State.Wait -> Message(stringResource(R.string.kb_wait)) { Pill(stringResource(R.string.kb_retry), dark = false) { sink.retry() } }
            }
        }
        // The paw's touchpad, tucked away at the right edge until it is wanted (most of the app is easier with the remote).
        if (state is KeyClient.State.Connected) PawDrawer(sink)
    }
}

/**
 * The touchpad drawer. Closed, only a small tab with a paw sticks out at the right edge, and the whole screen is the keyboard's. Pulled out
 * (or the tab touched) the pad slides in from the right, below the switch at the top; pushed back, or the tab or ✕ touched, it goes away.
 * While it is out the TV shows the paw, and it is the same paw as the remote's.
 */
@Composable
private fun BoxScope.PawDrawer(sink: KeySink) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    var open by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    fun settle(toOpen: Boolean) {
        if (toOpen != open) {
            open = toOpen
            if (!toOpen && dragging) { dragging = false; sink.pointer(KeyLink.Msg.Drag(false)) }
            sink.pointer(KeyLink.Msg.Pointer(toOpen))
        }
        scope.launch { shown.animateTo(if (toOpen) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = 500f)) }
    }
    DisposableEffect(Unit) { onDispose { if (open) sink.pointer(KeyLink.Msg.Pointer(false)) } }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.matchParentSize().padding(top = 76.dp, bottom = 8.dp)) {
            val density = LocalDensity.current
            val handle = 34.dp
            val panel = (maxWidth - 40.dp).coerceAtMost(480.dp)
            val panelPx = with(density) { panel.toPx() }
            val left = with(density) { (maxWidth - handle).toPx() } - panelPx * shown.value
            val drag = rememberDraggableState { delta -> scope.launch { shown.snapTo((shown.value - delta / panelPx).coerceIn(0f, 1f)) } }
            Row(
                Modifier.fillMaxHeight().absoluteOffset { IntOffset(left.roundToInt(), 0) }
                    .draggable(drag, Orientation.Horizontal, onDragStopped = { v -> settle(if (abs(v) > 600f) v < 0 else shown.value > 0.5f) }),
                verticalAlignment = Alignment.Bottom,
            ) {
                // The tab: a paw on the ink, at the edge of the screen, low, beside the big Enter (where the edge is free; higher up it
                // would sit on the row of keys).
                val tabLabel = stringResource(R.string.kb_pad_handle)
                Box(
                    Modifier.padding(bottom = 22.dp).width(handle).height(112.dp).clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)).background(Palette.Ink)
                        .clickable { settle(!open) }.semantics { role = Role.Button; contentDescription = tabLabel },
                    contentAlignment = Alignment.Center,
                ) { PawIcon(24.dp, Palette.Red, outline = Palette.Ink) }
                Column(
                    Modifier.width(panel).fillMaxHeight().clip(RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)).background(Palette.Card)
                        .border(BorderStroke(1.5.dp, Palette.Line2), RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PawIcon(22.dp, Palette.Red, outline = Palette.Card)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.kb_pad), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.weight(1f))
                            val close = stringResource(R.string.kb_pad_close)
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(Palette.Tint).clickable { settle(false) }
                                    .semantics { role = Role.Button; contentDescription = close },
                                contentAlignment = Alignment.Center,
                            ) { Text("✕", fontSize = 18.sp, color = Palette.Ink) }
                        }
                    }
                    TouchPad(sink, Modifier.fillMaxWidth().weight(1f)) { if (dragging) dragging = false }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val haptics = LocalHapticFeedback.current
                            PadButton(stringResource(R.string.kb_pad_click), on = false, Modifier.weight(1f)) {
                                dragging = false
                                sink.pointer(KeyLink.Msg.Click); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            PadButton(stringResource(if (dragging) R.string.kb_pad_drag_on else R.string.kb_pad_drag), on = dragging, Modifier.weight(1f)) {
                                dragging = !dragging
                                sink.pointer(KeyLink.Msg.Drag(dragging))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The pad itself: one finger moves the paw (faster swipes go further, like a laptop's), a short touch is a tap where the paw is, two fingers
 * scroll the page under it. Moves go out at most sixty times a second, each the sum of what happened since the last.
 */
@Composable
private fun TouchPad(sink: KeySink, modifier: Modifier, onTapped: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val hint = stringResource(R.string.kb_pad_hint)
    Box(
        modifier.clip(RoundedCornerShape(20.dp)).background(Palette.Tint).border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(20.dp))
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
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTapped()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        PawIcon(84.dp, Palette.Line, outline = Palette.Tint)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Text(hint, fontSize = 14.sp, color = Palette.Ink2, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }
}

@Composable
private fun PadButton(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Palette.Red else Palette.Ink).clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Cream, maxLines = 1) }
}

/** Playing here, or being the TV's keyboard: one switch, the same in the hub's header. */
@Composable
fun ModeSwitch(keys: Boolean, onGame: () -> Unit, onKeys: () -> Unit = {}) {
    val desc = stringResource(R.string.kb_mode_switch)
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(Palette.Tint)
            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(26.dp)).padding(4.dp).semantics { contentDescription = desc },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Segment(stringResource(R.string.kb_mode_game), selected = !keys, onClick = onGame)
        Segment(stringResource(R.string.kb_mode_keys), selected = keys, onClick = onKeys)
    }
}

@Composable
private fun RowScope.Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (selected) Palette.Ink else Palette.Tint)
            .clickable(onClick = onClick).semantics { role = Role.Tab; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Palette.Cream else Palette.Ink2) }
}

@Composable
private fun StatusLine(text: String, bold: String? = null, live: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PawIcon(26.dp, if (live) Palette.Red else Palette.Ink2, outline = Palette.Cream)
        Text(text, fontSize = 18.sp, color = Palette.Ink2)
        if (bold != null) Text(bold, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Connected: what is typed goes to the TV. */
@Composable
private fun ColumnScope.Typing(state: KeyClient.State.Connected, sink: KeySink) {
    var line by remember { mutableStateOf("") }
    var enters by remember { mutableIntStateOf(0) }
    var capture by remember { mutableStateOf<CaptureView?>(null) }
    StatusLine(stringResource(R.string.kb_connected) + " ·", state.screen.ifBlank { state.tv })

    // The line being typed, as the TV's field has it. The phone's keyboard types into the view laid over it; touching it brings the keyboard back.
    val hint = stringResource(R.string.kb_line_hint)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(18.dp)).background(Palette.Card)
            .border(BorderStroke(1.5.dp, Palette.Line2), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            line.ifEmpty { hint }, fontSize = if (line.isEmpty()) 18.sp else 28.sp, fontWeight = if (line.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
            color = if (line.isEmpty()) Palette.Ink2 else Palette.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
        AndroidView(
            factory = { ctx ->
                CaptureView(ctx).apply {
                    onEdit = { e ->
                        repeat(e.back) { sink.key(KeyLink.Key.BACKSPACE) }
                        if (e.text.isNotEmpty()) sink.text(e.text)
                    }
                    onEnter = { sink.key(KeyLink.Key.ENTER); enters++ }
                    onBackspace = { sink.key(KeyLink.Key.BACKSPACE) }
                    onLine = { line = it }
                    contentDescription = ctx.getString(R.string.kb_tap_to_type)
                    capture = this
                }
            },
            modifier = Modifier.matchParentSize(),
        )
    }
    LaunchedEffect(capture) { capture?.let { delay(250); it.showKeyboard() } }

    ExtraKeys(sink, onBackspace = { capture?.backspace() ?: sink.key(KeyLink.Key.BACKSPACE) }, onMoved = { capture?.newLine() })
    Spacer(Modifier.weight(1f))
    PawEnter(enters) { capture?.pressEnter() ?: run { sink.key(KeyLink.Key.ENTER); enters++ } }
}

/** The keys a game wants that a phone's keyboard does not have. Arrows can be held (the game sees the key down until it is let go). */
@Composable
private fun ExtraKeys(sink: KeySink, onBackspace: () -> Unit, onMoved: () -> Unit) {
    // Left and right here are the screen's own, whatever the language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallKey(stringResource(R.string.kb_esc), "Esc", 1.15f) { sink.key(KeyLink.Key.ESCAPE); onMoved() }
            SmallKey(stringResource(R.string.kb_tab), "Tab", 1.15f) { sink.key(KeyLink.Key.TAB); onMoved() }
            HoldKey("◀", stringResource(R.string.kb_left), KeyLink.Key.LEFT, sink, onMoved)
            HoldKey("▲", stringResource(R.string.kb_up), KeyLink.Key.UP, sink, onMoved)
            HoldKey("▼", stringResource(R.string.kb_down), KeyLink.Key.DOWN, sink, onMoved)
            HoldKey("▶", stringResource(R.string.kb_right), KeyLink.Key.RIGHT, sink, onMoved)
            SmallKey("⌫", stringResource(R.string.kb_backspace), 1.3f, onBackspace)
        }
    }
}

@Composable
private fun RowScope.SmallKey(label: String, description: String, weight: Float, onClick: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    Box(
        Modifier.weight(weight).height(54.dp).clip(RoundedCornerShape(14.dp)).background(if (down) Palette.Red else Palette.Tint)
            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(14.dp))
            .pointerInput(Unit) { detectTapGestures(onPress = { down = true; tryAwaitRelease(); down = false }, onTap = { onClick() }) }
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (down) Palette.Cream else Palette.Ink) }
}

@Composable
private fun RowScope.HoldKey(label: String, description: String, key: KeyLink.Key, sink: KeySink, onMoved: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    Box(
        Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(14.dp)).background(if (down) Palette.Red else Palette.Card)
            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(14.dp))
            .pointerInput(key) {
                detectTapGestures(onPress = {
                    down = true; onMoved(); sink.key(key, KeyLink.Action.DOWN)
                    tryAwaitRelease()
                    sink.key(key, KeyLink.Action.UP); down = false
                })
            }
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 18.sp, color = if (down) Palette.Cream else Palette.Ink) }
}

/**
 * The big Enter, with a cat's paw hovering over it. Each Enter (this key, or the phone keyboard's own) brings the paw down onto the key,
 * which gives a little under it, and the paw springs back up.
 */
@Composable
fun PawEnter(presses: Int, onClick: () -> Unit) {
    val press = remember { Animatable(0f) }
    LaunchedEffect(presses) {
        if (presses > 0) {
            press.snapTo(0f)
            press.animateTo(1f, tween(110, easing = FastOutLinearInEasing))
            press.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 420f))
        }
    }
    val p = press.value
    val description = stringResource(R.string.kb_enter_paw)
    Box(Modifier.fillMaxWidth().height(136.dp), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.width(240.dp).height(68.dp).offset { IntOffset(0, (3.dp.toPx() * p).roundToInt()) }) {
            // The hard red shadow of the app's main buttons: the key sinks into it when pressed.
            Box(
                Modifier.fillMaxSize().absoluteOffset { IntOffset((4.dp.toPx() * (1 - p)).roundToInt(), (4.dp.toPx() * (1 - p)).roundToInt()) }
                    .clip(RoundedCornerShape(18.dp)).background(Palette.Red),
            )
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(Palette.Ink).clickable(onClick = onClick)
                    .semantics { role = Role.Button; contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text("Enter ⏎", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Cream)
                }
            }
        }
        PawIcon(
            58.dp, Palette.Red, outline = Palette.Cream,
            modifier = Modifier.align(Alignment.TopCenter)
                .offset { IntOffset(0, (-4.dp.toPx() + 40.dp.toPx() * p).roundToInt()) }
                .graphicsLayer { rotationZ = -10f + 10f * p; scaleY = 1f - 0.14f * p; scaleX = 1f + 0.06f * p },
        )
    }
}

/** Looking for the TV: three paw prints walking. */
@Composable
private fun Looking(text: String) {
    val t = rememberInfiniteTransition(label = "walk")
    val step by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1_500, easing = LinearEasing), RepeatMode.Restart), label = "step")
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (i in 0 until 3) {
                val on = step >= i && step < i + 1.6f
                PawIcon(34.dp, if (on) Palette.Red else Palette.Line2, outline = Palette.Cream, modifier = Modifier.offset(y = if (i % 2 == 0) 0.dp else (-10).dp))
            }
        }
        Text(text, fontSize = 20.sp, color = Palette.Ink2, textAlign = TextAlign.Center)
    }
}

/** First time with this TV: the four digits it shows. */
@Composable
private fun CodeEntry(left: Int?, sink: KeySink) {
    val code = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { focus.requestFocus() } }
    LaunchedEffect(left) { if (left != null) code.clearText() }
    // Four digits typed: sent at once (no button to find).
    LaunchedEffect(code) { snapshotFlow { code.text.toString() }.collect { if (it.length == 4) sink.code(it) } }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.kb_code_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
        Text(stringResource(R.string.kb_code_body), fontSize = 17.sp, color = Palette.Ink2, textAlign = TextAlign.Center)
        BasicTextField(
            state = code,
            inputTransformation = InputTransformation.maxLengthReject(4).then(DigitsOnly),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            lineLimits = TextFieldLineLimits.SingleLine,
            textStyle = TextStyle(color = Palette.Cream),
            modifier = Modifier.focusRequester(focus).semantics { contentDescription = "קוד" },
            decorator = { inner ->
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box {
                        Box(Modifier.size(1.dp).graphicsLayer { alpha = 0f }) { inner() }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val typed = code.text
                            for (i in 0 until 4) {
                                Box(
                                    Modifier.size(58.dp, 70.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Card)
                                        .border(BorderStroke(2.dp, if (i == typed.length) Palette.Red else Palette.Line2), RoundedCornerShape(14.dp)),
                                    contentAlignment = Alignment.Center,
                                ) { Text(typed.getOrNull(i)?.toString().orEmpty(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Ink) }
                            }
                        }
                    }
                }
            },
        )
        if (left != null) Text(stringResource(R.string.kb_code_wrong, left), fontSize = 17.sp, color = Palette.Red, fontWeight = FontWeight.SemiBold)
    }
}

private val DigitsOnly = InputTransformation { if (!asCharSequence().all(Char::isDigit)) revertAllChanges() }

private val AddressChars = InputTransformation {
    if (!asCharSequence().all { it.isLetterOrDigit() || it == '.' || it == ':' || it == '[' || it == ']' || it == '%' }) revertAllChanges()
}

@Composable
private fun NotFound(reason: String, sink: KeySink) {
    if (reason == KeyClient.NO_PERMISSION) {
        Message(stringResource(R.string.kb_permission)) { Pill(stringResource(R.string.kb_permission_button), dark = true) { sink.permission() } }
        return
    }
    var typing by remember { mutableStateOf(false) }
    val address = rememberTextFieldState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Text(stringResource(R.string.kb_address_hint), fontSize = 16.sp, color = Palette.Ink2)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                BasicTextField(
                    state = address,
                    inputTransformation = InputTransformation.maxLengthReject(45).then(AddressChars),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    onKeyboardAction = { sink.address(address.text.toString()) },
                    lineLimits = TextFieldLineLimits.SingleLine, textStyle = TextStyle(fontSize = 24.sp, color = Palette.Ink),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Card)
                        .border(BorderStroke(1.5.dp, Palette.Line2), RoundedCornerShape(14.dp)).padding(14.dp),
                )
            }
            Pill(stringResource(R.string.kb_connect), dark = true) { sink.address(address.text.toString()) }
        }
    }
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PawIcon(40.dp, Palette.Red, outline = Palette.Cream)
        Text(text, fontSize = 19.sp, color = Palette.Ink, textAlign = TextAlign.Center)
        action()
    }
}

@Composable
private fun Pill(label: String, dark: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 50.dp).clip(RoundedCornerShape(14.dp)).background(if (dark) Palette.Ink else Palette.Tint)
            .border(BorderStroke(1.dp, if (dark) Palette.Ink else Palette.Line2), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 20.dp).semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (dark) Palette.Cream else Palette.Ink) }
}
