package app.couch.alhasapa.tv

import android.view.KeyEvent

/** One key on the on-screen keyboard. */
sealed interface KbKey {
    /** A character; [upper] is what Shift gives (the same for scripts without case). */
    data class Ch(val lower: String, val upper: String = lower.uppercase()) : KbKey
    data object Shift : KbKey
    data object Backspace : KbKey
    data object Enter : KbKey
    data object Space : KbKey
    data class Switch(val to: KbLayout, val label: String) : KbKey
    data object Close : KbKey
}

enum class KbLayout { ENGLISH, HEBREW, SYMBOLS }

/** How a typed key reaches the page: as a real key press (games listen for those) or as text (anything with no key code). */
sealed interface Delivery {
    data class Code(val keyCode: Int, val shift: Boolean = false) : Delivery
    data class Text(val text: String) : Delivery
}

object KbLayouts {
    private fun chars(s: String): List<KbKey> = s.map { KbKey.Ch(it.toString()) }
    private fun tail(self: KbLayout): List<KbKey> = buildList {
        if (self != KbLayout.ENGLISH) add(KbKey.Switch(KbLayout.ENGLISH, "ABC"))
        if (self != KbLayout.HEBREW) add(KbKey.Switch(KbLayout.HEBREW, "עב"))
        if (self != KbLayout.SYMBOLS) add(KbKey.Switch(KbLayout.SYMBOLS, "123"))
        add(KbKey.Space); add(KbKey.Ch("-")); add(KbKey.Ch(".")); add(KbKey.Ch("@")); add(KbKey.Close)
    }

    fun rows(layout: KbLayout): List<List<KbKey>> = when (layout) {
        KbLayout.ENGLISH -> listOf(
            chars("1234567890") + KbKey.Backspace,
            chars("qwertyuiop"),
            chars("asdfghjkl") + KbKey.Enter,
            listOf<KbKey>(KbKey.Shift) + chars("zxcvbnm") + KbKey.Ch(",") + KbKey.Ch("?"),
            tail(KbLayout.ENGLISH),
        )
        KbLayout.HEBREW -> listOf(
            chars("1234567890") + KbKey.Backspace,
            chars("קראטוןםפ"),
            chars("שדגכעיחלךף") + KbKey.Enter,
            chars("זסבהנמצתץ") + KbKey.Ch("?"),
            tail(KbLayout.HEBREW),
        )
        KbLayout.SYMBOLS -> listOf(
            chars("1234567890") + KbKey.Backspace,
            chars("!?:;'\"()/"),
            chars("#\$%&*+=_<>") + KbKey.Enter,
            chars("[]{}~^|\\`€"),
            tail(KbLayout.SYMBOLS),
        )
    }

    /** What pressing [key] sends to the page (null for keys that only act on the keyboard itself). */
    fun delivery(key: KbKey, shift: Boolean): Delivery? = when (key) {
        is KbKey.Ch -> {
            val s = if (shift) key.upper else key.lower
            val c = s.singleOrNull()
            when {
                c != null && c.lowercaseChar() in 'a'..'z' -> Delivery.Code(KeyEvent.KEYCODE_A + (c.lowercaseChar() - 'a'), c.isUpperCase())
                c != null && c in '0'..'9' -> Delivery.Code(KeyEvent.KEYCODE_0 + (c - '0'))
                else -> Delivery.Text(s)
            }
        }
        KbKey.Backspace -> Delivery.Code(KeyEvent.KEYCODE_DEL)
        KbKey.Enter -> Delivery.Code(KeyEvent.KEYCODE_ENTER)
        KbKey.Space -> Delivery.Code(KeyEvent.KEYCODE_SPACE)
        else -> null
    }
}

/** The highlighted key, moved with the arrows. Rows have different lengths, so the column is kept by position along the row. */
class KeyboardNav(private var rows: List<List<KbKey>> = KbLayouts.rows(KbLayout.ENGLISH)) {
    var row = 0; private set
    var col = 0; private set

    fun setRows(next: List<List<KbKey>>) { rows = next; row = row.coerceAtMost(rows.size - 1); col = col.coerceAtMost(rows[row].size - 1) }

    val key: KbKey get() = rows[row][col]

    fun move(d: Dir) {
        when (d) {
            Dir.LEFT -> col = (col - 1).coerceAtLeast(0)
            Dir.RIGHT -> col = (col + 1).coerceAtMost(rows[row].size - 1)
            Dir.UP, Dir.DOWN -> {
                val nr = (row + if (d == Dir.UP) -1 else 1).coerceIn(0, rows.size - 1)
                if (nr != row) {
                    val at = if (rows[row].size <= 1) 0f else col.toFloat() / (rows[row].size - 1)
                    row = nr
                    col = Math.round(at * (rows[row].size - 1)).coerceIn(0, rows[row].size - 1)
                }
            }
        }
    }

    fun moveTo(r: Int, c: Int) { row = r.coerceIn(0, rows.size - 1); col = c.coerceIn(0, rows[row].size - 1) }

    fun reset() { row = 1; col = 0 }
}
