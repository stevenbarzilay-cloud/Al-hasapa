package app.couch.alhasapa.keyboard

import app.couch.alhasapa.remote.KeyLink

/** One key of the phone's keyboard in keyboard mode. */
sealed interface PKey {
    /** A character; [upper] is what Shift gives (the same for Hebrew and symbols). */
    data class Ch(val lower: String, val upper: String = lower.uppercase()) : PKey
    data object Shift : PKey
    data object Esc : PKey
    data object Tab : PKey
    data object Space : PKey
    data class Switch(val to: PhoneLayout, val label: String) : PKey

    /** Room left empty so the rows line up like a real keyboard's. */
    data object Gap : PKey
}

enum class PhoneLayout { EN, HE, SYM }

/**
 * The keyboard of keyboard mode, sideways on the phone: five rows on the left (Esc and the digits; Tab and the top letters; the middle
 * letters; Shift and the bottom letters; the layout keys and space), and beside them a column with Backspace, a tall Enter (the paw's)
 * and the arrows. English as on a US keyboard, Hebrew as on an Israeli one, and a row of symbols for the rest.
 *
 * Each key has a width in "units"; every row adds up to [ROW_UNITS], so the rows line up.
 */
object PhoneKeys {
    const val ROW_UNITS = 11.3f
    private const val SIDE = 1.3f

    private fun chars(s: String) = s.map { PKey.Ch(it.toString()) to 1f }

    fun rows(layout: PhoneLayout): List<List<Pair<PKey, Float>>> {
        val digits = listOf(PKey.Esc to SIDE) + chars("1234567890")
        return when (layout) {
            PhoneLayout.EN -> listOf(
                digits,
                listOf(PKey.Tab to SIDE) + chars("qwertyuiop"),
                listOf(PKey.Gap to SIDE) + chars("asdfghjkl'"),
                listOf(PKey.Shift to SIDE) + chars("zxcvbnm,.?"),
                listOf(PKey.Switch(PhoneLayout.SYM, "123") to SIDE, PKey.Switch(PhoneLayout.HE, "עב") to SIDE, PKey.Space to 7.7f, PKey.Ch("@") to 1f),
            )
            PhoneLayout.HE -> listOf(
                digits,
                listOf(PKey.Tab to SIDE) + chars("/'קראטוןםפ"),
                listOf(PKey.Gap to SIDE) + chars("שדגכעיחלךף"),
                listOf(PKey.Ch(",") to SIDE) + chars("זסבהנמצתץ."),
                listOf(PKey.Switch(PhoneLayout.SYM, "123") to SIDE, PKey.Switch(PhoneLayout.EN, "EN") to SIDE, PKey.Space to 7.7f, PKey.Ch("?") to 1f),
            )
            PhoneLayout.SYM -> listOf(
                digits,
                listOf(PKey.Tab to SIDE) + chars("!@#$%^&*()"),
                listOf(PKey.Gap to SIDE) + chars("-_=+[]{};:"),
                listOf(PKey.Ch(",") to SIDE) + chars("'\"<>/\\|~`?"),
                listOf(PKey.Switch(PhoneLayout.EN, "ABC") to SIDE, PKey.Switch(PhoneLayout.HE, "עב") to SIDE, PKey.Space to 7.7f, PKey.Ch(".") to 1f),
            )
        }
    }

    /** What a key sends: text, or a key of the TV's; null for the keys that only change the keyboard (Shift, the layouts). */
    fun message(key: PKey, shift: Boolean): KeyLink.Msg? = when (key) {
        is PKey.Ch -> KeyLink.Msg.Text(if (shift) key.upper else key.lower)
        PKey.Space -> KeyLink.Msg.Text(" ")
        PKey.Esc -> KeyLink.Msg.Press(KeyLink.Key.ESCAPE, KeyLink.Action.TAP)
        PKey.Tab -> KeyLink.Msg.Press(KeyLink.Key.TAB, KeyLink.Action.TAP)
        else -> null
    }
}
