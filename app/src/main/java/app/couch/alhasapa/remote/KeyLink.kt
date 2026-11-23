package app.couch.alhasapa.remote

import android.view.KeyEvent
import java.net.Inet6Address
import java.net.InetAddress
import org.json.JSONObject

/**
 * The phone-as-keyboard link: what the phone and the TV say to each other over the home network, one small JSON object per line.
 *
 *     TV    → phone  hi {tv, name, nonce, app, ver}     first thing on a new connection; nonce: new each time (from 1.9)
 *     phone → TV     hello {phone, name, token, proof, app, ver}
 *                                                         proof = HMAC(token, nonce): the token itself is not sent (from 1.9;
 *                                                         before, the token was sent as is, still accepted for typing only)
 *                                                         app, ver: each side's app version (from 1.9.4), so each can say when
 *                                                         the other one was left behind on an old version
 *     TV    → phone  code                                 not paired yet: the TV shows four digits
 *     phone → TV     pair {code}
 *     TV    → phone  wrong {left} | paired {token}
 *     TV    → phone  ok {screen}, then screen {screen}    ready; what the TV is showing (for the phone's status line)
 *     phone → TV     text {s} | key {k, a} | ping
 *     phone → TV     pointer {on} | move {dx, dy, at} | click | drag {on} | scroll {dy} the touchpad (dp of the phone's pad;
 *                                                         at: when the finger moved, by the phone's clock, from 1.9.4)
 *     TV    → phone  pong | bye {why}
 *
 *     the sign-in handoff (from 1.9, see LoginHandoff and LoginCrypto):
 *     phone → TV     logins-key {key}                     a fresh public key
 *     TV    → phone  logins-ready {key, sig} | logins-no {why}
 *     phone → TV     logins {iv, data}                    the site's cookies, AES-GCM under the agreed key
 *     TV    → phone  logins-done {site, n} | logins-no {why}
 *
 * Only keys (and, when the people ask for it, a site's login) go to the TV, and only the screen's name comes back: nothing a page shows
 * or holds ever leaves the TV.
 */
object KeyLink {
    /** Advertised on the home network (DNS-SD), so the phone finds the TV by itself. */
    const val SERVICE_TYPE = "_couchkeys._tcp"

    /** The port the TV listens on when it is free (another one is advertised if not). */
    const val PORT = 47123

    const val VERSION = 2

    /** A line longer than this is not one of ours (a site's cookies, encrypted, fit well within it). */
    const val MAX_LINE = 65_536

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
        /** [app], [ver]: the sender's app version (its number and its name); 0 and empty from an app before 1.9.4. */
        data class Hi(val tv: String, val name: String, val nonce: String = "", val app: Int = 0, val ver: String = "") : Msg
        data class Hello(val phone: String, val name: String, val token: String, val proof: String = "", val app: Int = 0, val ver: String = "") : Msg
        data object NeedCode : Msg
        data class Pair(val code: String) : Msg
        data class Wrong(val left: Int) : Msg
        data class Paired(val token: String) : Msg
        data class Ok(val screen: String) : Msg
        data class Screen(val screen: String) : Msg
        data class Text(val s: String) : Msg
        data class Press(val key: Key, val action: Action) : Msg

        /** The touchpad came out ([on]) or went back: the TV's paw shows, or goes away. */
        data class Pointer(val on: Boolean) : Msg
        /** Move the paw by this much (in dp of the phone's touchpad). [t]: when the finger moved, in ms of the phone's own clock (0: not said). */
        data class Move(val dx: Float, val dy: Float, val t: Long = 0) : Msg
        /** A tap where the paw is. */
        data object Click : Msg
        /** The finger down where the paw is ([on]: moves are then a drag), or up again. */
        data class Drag(val on: Boolean) : Msg
        /** Two fingers moved [dy] dp on the touchpad: the page under the paw scrolls. */
        data class Scroll(val dy: Float) : Msg
        data object Ping : Msg
        data object Pong : Msg
        data class Bye(val why: String) : Msg

        /** The sign-in handoff: the phone's fresh public key; the TV's, signed with the pairing token; the logins; the answer. */
        data class LoginsKey(val key: String) : Msg
        data class LoginsReady(val key: String, val sig: String) : Msg
        data class Logins(val iv: String, val data: String) : Msg
        data class LoginsDone(val site: String, val n: Int) : Msg
        data class LoginsNo(val why: String) : Msg
    }

    fun encode(m: Msg): String = when (m) {
        is Msg.Hi -> JSONObject().put("t", "hi").put("v", VERSION).put("tv", m.tv).put("name", m.name).put("nonce", m.nonce)
            .put("app", m.app).put("ver", m.ver)
        is Msg.Hello -> JSONObject().put("t", "hello").put("v", VERSION).put("phone", m.phone).put("name", m.name).put("token", m.token)
            .put("proof", m.proof).put("app", m.app).put("ver", m.ver)
        Msg.NeedCode -> JSONObject().put("t", "code")
        is Msg.Pair -> JSONObject().put("t", "pair").put("code", m.code)
        is Msg.Wrong -> JSONObject().put("t", "wrong").put("left", m.left)
        is Msg.Paired -> JSONObject().put("t", "paired").put("token", m.token)
        is Msg.Ok -> JSONObject().put("t", "ok").put("screen", m.screen)
        is Msg.Screen -> JSONObject().put("t", "screen").put("screen", m.screen)
        is Msg.Text -> JSONObject().put("t", "text").put("s", m.s)
        is Msg.Press -> JSONObject().put("t", "key").put("k", m.key.name).put("a", m.action.name.lowercase())
        is Msg.Pointer -> JSONObject().put("t", "pointer").put("on", m.on)
        is Msg.Move -> JSONObject().put("t", "move").put("dx", round2(m.dx)).put("dy", round2(m.dy)).apply { if (m.t > 0) put("at", m.t) }
        Msg.Click -> JSONObject().put("t", "click")
        is Msg.Drag -> JSONObject().put("t", "drag").put("on", m.on)
        is Msg.Scroll -> JSONObject().put("t", "scroll").put("dy", round2(m.dy))
        Msg.Ping -> JSONObject().put("t", "ping")
        Msg.Pong -> JSONObject().put("t", "pong")
        is Msg.Bye -> JSONObject().put("t", "bye").put("why", m.why)
        is Msg.LoginsKey -> JSONObject().put("t", "logins-key").put("key", m.key)
        is Msg.LoginsReady -> JSONObject().put("t", "logins-ready").put("key", m.key).put("sig", m.sig)
        is Msg.Logins -> JSONObject().put("t", "logins").put("iv", m.iv).put("data", m.data)
        is Msg.LoginsDone -> JSONObject().put("t", "logins-done").put("site", m.site).put("n", m.n)
        is Msg.LoginsNo -> JSONObject().put("t", "logins-no").put("why", m.why)
    }.toString()

    /** Null for anything that is not one of the messages above (garbage, too long, an unknown key). */
    fun decode(line: String): Msg? {
        if (line.length > MAX_LINE) return null
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        fun s(k: String) = o.optString(k, "").take(512)
        return when (o.optString("t")) {
            "hi" -> Msg.Hi(s("tv"), s("name"), s("nonce"), o.optInt("app", 0).coerceAtLeast(0), s("ver").take(24))
            "hello" -> Msg.Hello(s("phone"), s("name"), s("token"), s("proof"), o.optInt("app", 0).coerceAtLeast(0), s("ver").take(24))
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
            "pointer" -> Msg.Pointer(o.optBoolean("on"))
            "move" -> Msg.Move(limit(o.optDouble("dx", 0.0)), limit(o.optDouble("dy", 0.0)), o.optLong("at", 0L).coerceAtLeast(0L))
            "click" -> Msg.Click
            "drag" -> Msg.Drag(o.optBoolean("on"))
            "scroll" -> Msg.Scroll(limit(o.optDouble("dy", 0.0)))
            "ping" -> Msg.Ping
            "pong" -> Msg.Pong
            "bye" -> Msg.Bye(s("why"))
            "logins-key" -> Msg.LoginsKey(s("key"))
            "logins-ready" -> Msg.LoginsReady(s("key"), s("sig"))
            "logins" -> o.optString("data", "").takeIf { it.isNotEmpty() }?.let { Msg.Logins(s("iv"), it) }
            "logins-done" -> Msg.LoginsDone(s("site"), o.optInt("n", 0))
            "logins-no" -> Msg.LoginsNo(s("why"))
            else -> null
        }
    }

    /** A move or a scroll is never more than this many dp at once (and never "not a number"). */
    private fun limit(v: Double): Float = if (v.isNaN()) 0f else v.coerceIn(-400.0, 400.0).toFloat()

    private fun round2(v: Float): Double = Math.round(v * 100.0) / 100.0

    /** Only devices in the home itself may connect: private, link-local and loopback addresses. */
    fun isHomeAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        // IPv6 unique local addresses (fc00::/7) are the home's own too.
        return a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc
    }
}
