package app.couch.alhasapa.remote

import android.view.KeyEvent
import app.couch.alhasapa.tv.Delivery

/**
 * What typed text becomes on the TV: everything a US keyboard has a key for goes in as that key press (with Shift where it needs it), which
 * is what a desktop game that listens for keys expects; anything else (Hebrew, emoji) is inserted into the focused field as text.
 */
object TypedKeys {
    private val punctuation: Map<Char, Pair<Int, Boolean>> = mapOf(
        ' ' to (KeyEvent.KEYCODE_SPACE to false), '\n' to (KeyEvent.KEYCODE_ENTER to false), '\t' to (KeyEvent.KEYCODE_TAB to false),
        '-' to (KeyEvent.KEYCODE_MINUS to false), '_' to (KeyEvent.KEYCODE_MINUS to true),
        '=' to (KeyEvent.KEYCODE_EQUALS to false), '+' to (KeyEvent.KEYCODE_EQUALS to true),
        '[' to (KeyEvent.KEYCODE_LEFT_BRACKET to false), '{' to (KeyEvent.KEYCODE_LEFT_BRACKET to true),
        ']' to (KeyEvent.KEYCODE_RIGHT_BRACKET to false), '}' to (KeyEvent.KEYCODE_RIGHT_BRACKET to true),
        '\\' to (KeyEvent.KEYCODE_BACKSLASH to false), '|' to (KeyEvent.KEYCODE_BACKSLASH to true),
        ';' to (KeyEvent.KEYCODE_SEMICOLON to false), ':' to (KeyEvent.KEYCODE_SEMICOLON to true),
        '\'' to (KeyEvent.KEYCODE_APOSTROPHE to false), '"' to (KeyEvent.KEYCODE_APOSTROPHE to true),
        ',' to (KeyEvent.KEYCODE_COMMA to false), '<' to (KeyEvent.KEYCODE_COMMA to true),
        '.' to (KeyEvent.KEYCODE_PERIOD to false), '>' to (KeyEvent.KEYCODE_PERIOD to true),
        '/' to (KeyEvent.KEYCODE_SLASH to false), '?' to (KeyEvent.KEYCODE_SLASH to true),
        '`' to (KeyEvent.KEYCODE_GRAVE to false), '~' to (KeyEvent.KEYCODE_GRAVE to true),
        '!' to (KeyEvent.KEYCODE_1 to true), '@' to (KeyEvent.KEYCODE_2 to true), '#' to (KeyEvent.KEYCODE_3 to true),
        '$' to (KeyEvent.KEYCODE_4 to true), '%' to (KeyEvent.KEYCODE_5 to true), '^' to (KeyEvent.KEYCODE_6 to true),
        '&' to (KeyEvent.KEYCODE_7 to true), '*' to (KeyEvent.KEYCODE_8 to true), '(' to (KeyEvent.KEYCODE_9 to true),
        ')' to (KeyEvent.KEYCODE_0 to true),
    )

    /** The key press that types [c], or null when no key on a US keyboard does. */
    fun keyFor(c: Char): Delivery.Code? = when (c) {
        in 'a'..'z' -> Delivery.Code(KeyEvent.KEYCODE_A + (c - 'a'))
        in 'A'..'Z' -> Delivery.Code(KeyEvent.KEYCODE_A + (c - 'A'), shift = true)
        in '0'..'9' -> Delivery.Code(KeyEvent.KEYCODE_0 + (c - '0'))
        else -> punctuation[c]?.let { (code, shift) -> Delivery.Code(code, shift) }
    }

    /** [s] in order: key presses where there are keys, and the rest as text, run together so a Hebrew word goes in at once. */
    fun deliveries(s: String): List<Delivery> {
        val out = ArrayList<Delivery>()
        val text = StringBuilder()
        fun flush() { if (text.isNotEmpty()) { out += Delivery.Text(text.toString()); text.clear() } }
        for (c in s) {
            val key = keyFor(c)
            if (key != null) { flush(); out += key } else text.append(c)
        }
        flush()
        return out
    }

    fun forKey(key: KeyLink.Key, action: KeyLink.Action): Delivery = when (action) {
        KeyLink.Action.TAP -> Delivery.Code(key.code)
        KeyLink.Action.DOWN -> Delivery.Hold(key.code, down = true)
        KeyLink.Action.UP -> Delivery.Hold(key.code, down = false)
    }
}
