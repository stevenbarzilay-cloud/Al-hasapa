package app.couch.alhasapa.tv

import kotlin.math.min
import kotlin.math.sqrt

enum class Dir { LEFT, UP, RIGHT, DOWN }

/** Where the virtual finger goes. The Android side turns these into touch events. */
interface TouchSink {
    fun tap(x: Float, y: Float)
    fun down(x: Float, y: Float)
    fun move(x: Float, y: Float)
    fun up(x: Float, y: Float)
}

/**
 * The remote as a finger. The arrows move a cursor (slowly at first, faster the longer an arrow is held); OK is a tap; holding
 * OK picks the finger up and puts it down ("drag mode": move, with every move a drag, and OK again lets go). No Android in here,
 * so it is tested with a recording sink and a fake clock ([tick]).
 *
 * Drag mode is a latch because a remote's centre button cannot be held while an arrow is pressed.
 */
class CursorController(private val density: Float) {
    var width = 0f; private set
    var height = 0f; private set
    var x = 0f; private set
    var y = 0f; private set
    var dragging = false; private set

    private val held = ArrayList<Dir>(2)
    private var heldMs = 0L
    private var okPressed = false
    private var okLong = false

    val moving: Boolean get() = held.isNotEmpty()

    /** The screen's size: the cursor starts in the middle and stays inside. */
    fun resize(w: Float, h: Float) {
        val first = width == 0f && height == 0f
        width = w; height = h
        if (first) { x = w / 2f; y = h / 2f } else { x = x.coerceIn(0f, w); y = y.coerceIn(0f, h) }
    }

    /** Speed in dp per second after the arrow has been held [heldMs]: precise at first, quick after a second. */
    fun speedDpPerSec(heldMs: Long = this.heldMs): Float = 160f + 900f * min(heldMs, RAMP_MS) / RAMP_MS

    fun jumpTo(nx: Float, ny: Float) { x = nx.coerceIn(0f, width); y = ny.coerceIn(0f, height) }

    /** An arrow went down. Returns true when this started the movement (the caller then starts its ticker). */
    fun keyDown(d: Dir, sink: TouchSink): Boolean {
        if (d in held) return false
        val wasIdle = held.isEmpty()
        held.add(d)
        if (wasIdle) heldMs = 0L
        // A short tap on an arrow still moves a visible little step, for fine positioning.
        move(vx() * NUDGE_DP * density, vy() * NUDGE_DP * density, sink)
        return wasIdle
    }

    fun keyUp(d: Dir) {
        held.remove(d)
        if (held.isEmpty()) heldMs = 0L
    }

    /** Advance by [dtMs] with the arrows as they are. Returns whether the cursor moved. */
    fun tick(dtMs: Long, sink: TouchSink): Boolean {
        if (held.isEmpty()) return false
        heldMs += dtMs
        val step = speedDpPerSec() * density * dtMs / 1000f
        val (ux, uy) = unit()
        val before = x to y
        move(ux * step, uy * step, sink)
        return (x to y) != before
    }

    /** OK went down; [repeat] is the key's repeat count (0 for the first press, 1 or more while it is held). */
    fun okDown(repeat: Int, sink: TouchSink) {
        if (repeat == 0) { okPressed = true; okLong = false; return }
        if (okPressed && !okLong) {
            okLong = true
            if (dragging) { dragging = false; sink.up(x, y) } else { dragging = true; sink.down(x, y) }
        }
    }

    /** OK went up: a short press is a tap. */
    fun okUp(sink: TouchSink) {
        if (okPressed && !okLong) sink.tap(x, y)
        okPressed = false; okLong = false
    }

    /** A move from the phone's touchpad: by [dx], [dy] pixels (a drag goes along if the finger is down). */
    fun moveBy(dx: Float, dy: Float, sink: TouchSink) = move(dx, dy, sink)

    /** The phone's drag switch: the finger goes down where the cursor is ([down]), or up again. */
    fun press(down: Boolean, sink: TouchSink) {
        if (down && !dragging) { dragging = true; sink.down(x, y) } else if (!down && dragging) { dragging = false; sink.up(x, y) }
    }

    /** A tap where the cursor is (a short touch on the phone's touchpad). A finger that was down is lifted first. */
    fun tapHere(sink: TouchSink) {
        if (dragging) { dragging = false; sink.up(x, y) }
        sink.tap(x, y)
    }

    /** Let go of everything (the keyboard opened, the app paused). */
    fun cancel(sink: TouchSink) {
        held.clear(); heldMs = 0L; okPressed = false; okLong = false
        if (dragging) { dragging = false; sink.up(x, y) }
    }

    private fun vx(): Float = (if (Dir.RIGHT in held) 1f else 0f) - (if (Dir.LEFT in held) 1f else 0f)
    private fun vy(): Float = (if (Dir.DOWN in held) 1f else 0f) - (if (Dir.UP in held) 1f else 0f)

    private fun unit(): Pair<Float, Float> {
        val a = vx(); val b = vy()
        val len = sqrt(a * a + b * b)
        return if (len == 0f) 0f to 0f else a / len to b / len
    }

    private fun move(dx: Float, dy: Float, sink: TouchSink) {
        val nx = (x + dx).coerceIn(0f, width)
        val ny = (y + dy).coerceIn(0f, height)
        if (nx == x && ny == y) return
        x = nx; y = ny
        if (dragging) sink.move(x, y)
    }

    companion object {
        const val RAMP_MS = 1_500L
        const val NUDGE_DP = 10f
        const val TICK_MS = 16L
    }
}
