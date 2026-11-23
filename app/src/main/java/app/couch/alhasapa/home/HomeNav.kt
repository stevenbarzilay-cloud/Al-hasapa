package app.couch.alhasapa.home

import app.couch.alhasapa.tv.Dir
import app.couch.alhasapa.tv.ScreenNav

/**
 * How the remote's arrows move on the TV's home: by its shape, not by where things happen to be on the screen. The home is rows (the
 * shelf, the rest of the games, the buttons at the foot); Left and Right walk along a row to its very end, wherever the cards are
 * scrolled; Up and Down change rows, and each row remembers where it was left, like the rows of a TV's own home screen.
 *
 * The app is right to left, so a row's first card is at the right and Left moves on to the next ([rtl] false would mirror that).
 * At a row's ends and above the first row or under the last, the focus stays where it is. Pure: no Android, no Compose.
 */
class HomeNav(private val rtl: Boolean = true) : ScreenNav {
    /** The rows, top to bottom, each from its first button to its last. Set by the home each time it is drawn. */
    var rows: List<List<String>> = emptyList()

    /** What OK does on a button of the home. */
    var onOk: (id: String) -> Unit = {}

    /** The button last focused in each row (by id: the shelf reorders itself when a game is played), and overall. */
    private val kept = HashMap<Int, String>()
    var last: String? = null
        private set

    /** The row and place of [id], or null when it is not one of the home's. */
    fun position(id: String?): Pair<Int, Int>? {
        if (id == null) return null
        rows.forEachIndexed { r, row -> val c = row.indexOf(id); if (c >= 0) return r to c }
        return null
    }

    /** Where the focus is put when it is nowhere: back where it was, else the first button of the first row. */
    fun home(): String? = last?.takeIf { position(it) != null } ?: rows.firstOrNull { it.isNotEmpty() }?.first()

    /** The focus is on [id] now (however it got there): remembered for its row. */
    fun note(id: String?) {
        val (r, _) = position(id) ?: return
        kept[r] = id!!
        last = id
    }

    override fun move(focused: String?, dir: Dir): String? {
        val (r, c) = position(focused) ?: return home()                 // the focus is nowhere on the home: any arrow brings it in
        val forward = if (rtl) Dir.LEFT else Dir.RIGHT
        val back = if (rtl) Dir.RIGHT else Dir.LEFT
        val next = when (dir) {
            forward -> rows[r].getOrNull(c + 1)
            back -> rows[r].getOrNull(c - 1)
            Dir.UP -> rowAt(r, -1)
            else -> rowAt(r, +1)
        }
        if (next != null) note(next)
        return next ?: ScreenNav.STAY
    }

    /** The button to land on in the nearest row that has any, [step] rows from [r]: where that row was left, else its first. */
    private fun rowAt(r: Int, step: Int): String? {
        var i = r + step
        while (i in rows.indices) {
            val row = rows[i]
            if (row.isNotEmpty()) return kept[i]?.takeIf { it in row } ?: row.first()
            i += step
        }
        return null
    }

    override fun ok(focused: String?): Boolean {
        if (position(focused) == null) return false
        onOk(focused!!)
        return true
    }
}
