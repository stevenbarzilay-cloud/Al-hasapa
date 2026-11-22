package app.couch.alhasapa.remote

import android.view.KeyEvent
import java.net.Inet6Address
import java.net.InetAddress
import org.json.JSONObject

/**
 * The phone-as-keyboard link: what the phone and the TV say to each other over the home network, one small JSON object per line.
 *
 *     TV    → phone  hi {tv, name}                      first thing on a new connection
 *     phone → TV     hello {phone, name, token}          token: from an earlier pairing, or empty
 *     TV    → phone  code                                 not paired yet: the TV shows four digits
 *     phone → TV     pair {code}
 *     TV    → phone  wrong {left} | paired {token}
 *     TV    → phone  ok {screen}, then screen {screen}    ready; what the TV is showing (for the phone's status line)
 *     phone → TV     text {s} | key {k, a} | ping
 *     TV    → phone  pong | bye {why}
 *
 * Only keys go to the TV and only the screen's name comes back: nothing a page shows or holds ever leaves the TV.
 */
object KeyLink {
    /** Advertised on the home network (DNS-SD), so the phone finds the TV by itself. */
    const val SERVICE_TYPE = "_couchkeys._tcp"

    /** The port the TV listens on when it is free (another one is advertised if not). */
    const val PORT = 47123

    const val VERSION = 1

    /** A line longer than this is not one of ours. */
    const val MAX_LINE = 4_096

    /** The keys the phone sends besides text. */
    enum class Key(val code: Int) {
        ENTER(KeyEvent.KEYCODE_ENTER),
        BACKSPACE(KeyEvent.KEYCODE_DEL),
        DELETE(KeyEvent.KEYCODE_FORWARD_DEL),
        TAB(KeyEvent.KEYCODE_TAB),
        ESCAPE(KeyEvent.KEYCODE_ESCAPE),
        SPACE(KeyEvent.KEYCODE_SPACE),
        UP(KeyEvent.KEYCODE_DPAD_UP),
        DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
        LEFT(KeyEvent.KEYCODE_DPAD_LEFT),
        RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),
    }

    /** A key is pressed and let go ([TAP]), or held ([DOWN] now, [UP] later: arrows in a game that moves while they are held). */
    enum class Action { TAP, DOWN, UP }

    sealed interface Msg {
        data class Hi(val tv: String, val name: String) : Msg
        data class Hello(val phone: String, val name: String, val token: String) : Msg
        data object NeedCode : Msg
        data class Pair(val code: String) : Msg
        data class Wrong(val left: Int) : Msg
        data class Paired(val token: String) : Msg
        data class Ok(val screen: String) : Msg
        data class Screen(val screen: String) : Msg
        data class Text(val s: String) : Msg
        data class Press(val key: Key, val action: Action) : Msg
        data object Ping : Msg
        data object Pong : Msg
        data class Bye(val why: String) : Msg
    }

    fun encode(m: Msg): String = when (m) {
        is Msg.Hi -> JSONObject().put("t", "hi").put("v", VERSION).put("tv", m.tv).put("name", m.name)
        is Msg.Hello -> JSONObject().put("t", "hello").put("v", VERSION).put("phone", m.phone).put("name", m.name).put("token", m.token)
        Msg.NeedCode -> JSONObject().put("t", "code")
        is Msg.Pair -> JSONObject().put("t", "pair").put("code", m.code)
        is Msg.Wrong -> JSONObject().put("t", "wrong").put("left", m.left)
        is Msg.Paired -> JSONObject().put("t", "paired").put("token", m.token)
        is Msg.Ok -> JSONObject().put("t", "ok").put("screen", m.screen)
        is Msg.Screen -> JSONObject().put("t", "screen").put("screen", m.screen)
        is Msg.Text -> JSONObject().put("t", "text").put("s", m.s)
        is Msg.Press -> JSONObject().put("t", "key").put("k", m.key.name).put("a", m.action.name.lowercase())
        Msg.Ping -> JSONObject().put("t", "ping")
        Msg.Pong -> JSONObject().put("t", "pong")
        is Msg.Bye -> JSONObject().put("t", "bye").put("why", m.why)
    }.toString()

    /** Null for anything that is not one of the messages above (garbage, too long, an unknown key). */
    fun decode(line: String): Msg? {
        if (line.length > MAX_LINE) return null
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        fun s(k: String) = o.optString(k, "").take(512)
        return when (o.optString("t")) {
            "hi" -> Msg.Hi(s("tv"), s("name"))
            "hello" -> Msg.Hello(s("phone"), s("name"), s("token"))
            "code" -> Msg.NeedCode
            "pair" -> Msg.Pair(s("code"))
            "wrong" -> Msg.Wrong(o.optInt("left", 0))
            "paired" -> Msg.Paired(s("token"))
            "ok" -> Msg.Ok(s("screen"))
            "screen" -> Msg.Screen(s("screen"))
            "text" -> o.optString("s", "").takeIf { it.isNotEmpty() && it.length <= 1_000 }?.let { Msg.Text(it) }
            "key" -> {
                val key = runCatching { Key.valueOf(o.optString("k")) }.getOrNull() ?: return null
                val action = runCatching { Action.valueOf(o.optString("a", "tap").uppercase()) }.getOrNull() ?: return null
                Msg.Press(key, action)
            }
            "ping" -> Msg.Ping
            "pong" -> Msg.Pong
            "bye" -> Msg.Bye(s("why"))
            else -> null
        }
    }

    /** Only devices in the home itself may connect: private, link-local and loopback addresses. */
    fun isHomeAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        // IPv6 unique local addresses (fc00::/7) are the home's own too.
        return a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc
    }
}
