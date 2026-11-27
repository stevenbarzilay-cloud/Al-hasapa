package app.couch.alhasapa.tv

import android.view.InputDevice
import android.view.MotionEvent

/**
 * The touches the app makes for the paw (and for OK on a page's button), as a finger makes them: a finger tool, pressure while it is
 * down, a fingertip's size. Until 1.9.5 they were Android's simplest kind of touch, whose tool is "unknown": a browser gives such a touch
 * to a page with no pointer type, which some games' drag code does not take for a finger, so dragging worked in some games and not in
 * others.
 */
object Touches {
    /** A one-finger touch at [x], [y] (in the coordinates of the view it is given to). */
    fun finger(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
            size = 0.04f
            touchMajor = FINGER_PX; touchMinor = FINGER_PX; toolMajor = FINGER_PX; toolMinor = FINGER_PX
        }
        return MotionEvent.obtain(downTime, eventTime, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    /** A turn of a mouse's wheel at [x], [y]: [vertical] notches, positive turning it up (the page's content comes down). */
    fun wheel(time: Long, x: Float, y: Float, vertical: Float): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; setAxisValue(MotionEvent.AXIS_VSCROLL, vertical) }
        return MotionEvent.obtain(time, time, MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    private const val FINGER_PX = 18f
}
