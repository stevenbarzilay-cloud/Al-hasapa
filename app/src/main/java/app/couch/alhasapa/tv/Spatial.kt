package app.couch.alhasapa.tv

import kotlin.math.abs
import kotlin.math.max

/** A rectangle in window pixels. */
data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val cx: Float get() = (l + r) / 2f
    val cy: Float get() = (t + b) / 2f
    val w: Float get() = r - l
    val h: Float get() = b - t
}

/** Something the arrows can land on. */
data class NavTarget(val id: String, val box: Box)

/**
 * Which thing the focus moves to when an arrow is pressed, the way a person expects it (like Tab, but in the direction pressed):
 * among the things that lie that way, the nearest, preferring the ones lined up with where you are. Pure, no Android.
 */
object Spatial {
    fun next(from: Box, candidates: List<NavTarget>, dir: Dir, exclude: String? = null): NavTarget? {
        val inDirection = candidates.filter { it.id != exclude && lies(from, it.box, dir) }
        if (inDirection.isEmpty()) return null
        // Things lined up with the current one (side by side for Left/Right, one above the other for Up/Down) come first.
        val lined = inDirection.filter { overlapPerp(from, it.box, dir) > 0f }
        val pool = if (lined.isNotEmpty()) lined else inDirection
        // Things wholly past the edge (the next row, the next bar) come before things that merely overlap the current one's span.
        val beyond = pool.filter { clear(from, it.box, dir) }
        return (if (beyond.isNotEmpty()) beyond else pool).minByOrNull { score(from, it.box, dir) }
    }

    private fun clear(a: Box, b: Box, dir: Dir): Boolean = when (dir) {
        Dir.RIGHT -> b.l >= a.r
        Dir.LEFT -> b.r <= a.l
        Dir.DOWN -> b.t >= a.b
        Dir.UP -> b.b <= a.t
    }

    private fun lies(a: Box, b: Box, dir: Dir): Boolean = when (dir) {
        Dir.RIGHT -> b.cx > a.cx + 1f
        Dir.LEFT -> b.cx < a.cx - 1f
        Dir.DOWN -> b.cy > a.cy + 1f
        Dir.UP -> b.cy < a.cy - 1f
    }

    private fun overlapPerp(a: Box, b: Box, dir: Dir): Float = when (dir) {
        Dir.LEFT, Dir.RIGHT -> max(0f, minOf(a.b, b.b) - max(a.t, b.t))
        Dir.UP, Dir.DOWN -> max(0f, minOf(a.r, b.r) - max(a.l, b.l))
    }

    private fun score(a: Box, b: Box, dir: Dir): Float {
        val along = when (dir) {
            Dir.RIGHT -> max(0f, b.l - a.r)
            Dir.LEFT -> max(0f, a.l - b.r)
            Dir.DOWN -> max(0f, b.t - a.b)
            Dir.UP -> max(0f, a.t - b.b)
        }
        val off = when (dir) {
            Dir.LEFT, Dir.RIGHT -> abs(b.cy - a.cy)
            Dir.UP, Dir.DOWN -> abs(b.cx - a.cx)
        }
        val centreAlong = when (dir) {
            Dir.RIGHT, Dir.LEFT -> abs(b.cx - a.cx)
            Dir.UP, Dir.DOWN -> abs(b.cy - a.cy)
        }
        return along + 0.35f * centreAlong + 2f * off
    }
}
