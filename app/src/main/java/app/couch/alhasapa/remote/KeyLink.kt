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
 *                                                         app: the version at which the two last had to be updated together
 *                                                         ([TOGETHER_SINCE]); ver: the app's version, to name it. From 1.9.4,
 *                                                         so each side can say when the other was left behind
 *     TV    → phone  code                                 not paired yet: the TV shows four digits
 *     phone → TV     pair {code}
 *     TV    → phone  wrong {left} | paired {token}
 *     TV    → phone  ok {screen}, then screen {screen, result, touch}  ready; what the TV is showing (for the phone's status
 *                                                         line); result: a game's end screen is up (from 1.9.7), and the phone
 *                                                         offers to save the result; touch: the game is played by dragging, and
 *                                                         the phone turns to touch mode by itself (from 1.9.8)
 *     phone → TV     save-result                          press "save the result" on the TV (from 1.9.7)
 *     phone → TV     text {s} | key {k, a} | ping
 *     phone → TV     pointer {on} | move {dx, dy, at} | click | drag {on} | scroll {dy} the touchpad (dp of the phone's pad;
 *                                                         at: when the finger moved, by the phone's clock, from 1.9.4)
 *     TV    → phone  pong | bye {why}
 *
 *     touch mode (from 1.9.8): the phone pictures the TV's game and is touched as if the game were on it
 *     phone → TV     mirror {on, w}                       pictures wanted (w: how wide the phone draws them, in pixels), or no more
 *     TV    → phone  frame {n, w, h, iv, data}            picture n of the game's page: a JPEG, AES-GCM under the connection's
 *                                                         picture key (see FrameCrypto); only to a phone that proved its pairing
 *     phone → TV     frame-ok {n}                         picture n is on the phone's screen: the next one may come (one at a time)
 *     TV    → phone  frame-none {why}                     nothing to picture: "home" (no game is open), "capture" (the TV could not)
 *     phone → TV     touch {a, x, y}                      a finger on the picture: down / move / up / cancel, at shares (0 to 1)
 *                                                         of the page's width and height
 *
 *     the sign-in handoff (from 1.9, see LoginHandoff and LoginCrypto):
 *     phone → TV     logins-key {key}                     a fresh public key
 *     TV    → phone  logins-ready {key, sig} | logins-no {why}
 *     phone → TV     logins {iv, data}                    the site's cookies, AES-GCM under the agreed key
 *     TV    → phone  logins-done {site, n} | logins-no {why}
 *
 * Only keys, touches (and, when the people ask for it, a site's login) go to the TV. What comes back is the screen's name and, in touch
 * mode, while the phone shows it and asks for it, a picture of the game's page, sealed for that phone. Nothing a page holds (its text,
 * its cookies, what is typed into it) ever leaves the TV.
 */
object KeyLink {
    /** Advertised on the home network (DNS-SD), so the phone finds the TV by itself. */
    const val SERVICE_TYPE = "_couchkeys._tcp"

    /** The port the TV listens on when it is free (another one is advertised if not). */
    const val PORT = 47123

    const val VERSION = 2

    /**
     * What each side tells the other as its "app" number: the app version at which the phone's app and the TV's last had to be
     * updated together (28 is 1.9.4, which first said it at all). A side that hears a lower number says the other one should be
     * updated; one that hears a higher number says so of itself.
     *
     * Not simply the app's own version number: a release that changes only one of the two (the TV's home got a new look in 1.9.5)
     * must not send the people to update the other for nothing. It is raised only when a release needs both.
     */
    const val TOGETHER_SINCE = 32

    /** A line longer than this is not one of ours (a site's cookies, encrypted, fit well within it). */
    const val MAX_LINE = 65_536

    /**
     * A line with a picture in it (touch mode) may be this long; only the phone reads such lines, so the TV's own limit stays [MAX_LINE].
     * A picture is a JPEG of at most [MAX_FRAME_WIDTH] pixels across, usually 30 to 100 KB.
     */
    const val MAX_FRAME_LINE = 2_000_000

    /** The widest picture the TV sends, in pixels (wider costs the box more to make than it adds on a phone's screen). */
    const val MAX_FRAME_WIDTH = 960

    /** Why there is no picture: no game is open on the TV ([NONE_HOME]); the TV could not take one ([NONE_CAPTURE]). */
    const val NONE_HOME = "home"
    const val NONE_CAPTURE = "capture"

    /** A finger on the phone's picture of the game: put down, moved, lifted, or taken away (another gesture took over). */
    enum class TouchAction { DOWN, MOVE, UP, CANCEL }

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
        /** [touch]: the game in front is played by dragging (from 1.9.8): the phone turns to touch mode by itself. */
        data class Screen(val screen: String, val result: Boolean = false, val touch: Boolean = false) : Msg

        /** Press "save the result" on the TV (offered on the phone while [Screen.result] is true). */
        data object SaveResult : Msg
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

        /** Touch mode: pictures of the game are wanted ([on]), [w] pixels wide, or no more. */
        data class Mirror(val on: Boolean, val w: Int = 0) : Msg
        /** Picture [n] of the game's page, [w] by [h] pixels: a JPEG sealed with the connection's picture key ([FrameCrypto]). */
        data class Frame(val n: Int, val w: Int, val h: Int, val iv: String, val data: String) : Msg
        /** Picture [n] is on the phone's screen: the next one may come. */
        data class FrameOk(val n: Int) : Msg
        /** There is nothing to picture ([NONE_HOME], [NONE_CAPTURE]). */
        data class FrameNone(val why: String) : Msg
        /** A finger on the picture: [a] at [x], [y], shares (0 to 1) of the page's width and height. */
        data class Touch(val a: TouchAction, val x: Float, val y: Float) : Msg

        /** The sign-in handoff: the phone's fresh public key; the TV's, signed with the pairing token; the logins; the answer. */
        data class LoginsKey(val key: String) : Msg
        data class LoginsReady(val key: String, val sig: String) : Msg
        data class Logins(val iv: String, val data: String) : Msg
        data class LoginsDone(val site: String, val n: Int) : Msg
        data class LoginsNo(val why: String) : Msg
    }

    fun encode(m: Msg): String {
        // A picture is large: its line is put together by hand when its parts are plain Base64 (nothing in them needs escaping).
        if (m is Msg.Frame && isBase64(m.iv) && isBase64(m.data)) return frameLine(m)
        return encodeJson(m).toString()
    }

    private fun encodeJson(m: Msg): JSONObject = when (m) {
        is Msg.Hi -> JSONObject().put("t", "hi").put("v", VERSION).put("tv", m.tv).put("name", m.name).put("nonce", m.nonce)
            .put("app", m.app).put("ver", m.ver)
        is Msg.Hello -> JSONObject().put("t", "hello").put("v", VERSION).put("phone", m.phone).put("name", m.name).put("token", m.token)
            .put("proof", m.proof).put("app", m.app).put("ver", m.ver)
        Msg.NeedCode -> JSONObject().put("t", "code")
        is Msg.Pair -> JSONObject().put("t", "pair").put("code", m.code)
        is Msg.Wrong -> JSONObject().put("t", "wrong").put("left", m.left)
        is Msg.Paired -> JSONObject().put("t", "paired").put("token", m.token)
        is Msg.Ok -> JSONObject().put("t", "ok").put("screen", m.screen)
        is Msg.Screen -> JSONObject().put("t", "screen").put("screen", m.screen).apply { if (m.result) put("result", true); if (m.touch) put("touch", true) }
        Msg.SaveResult -> JSONObject().put("t", "save-result")
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
        is Msg.Mirror -> JSONObject().put("t", "mirror").put("on", m.on).put("w", m.w)
        is Msg.Frame -> JSONObject().put("t", "frame").put("n", m.n).put("w", m.w).put("h", m.h).put("iv", m.iv).put("data", m.data)
        is Msg.FrameOk -> JSONObject().put("t", "frame-ok").put("n", m.n)
        is Msg.FrameNone -> JSONObject().put("t", "frame-none").put("why", m.why)
        is Msg.Touch -> JSONObject().put("t", "touch").put("a", m.a.name.lowercase()).put("x", round4(m.x)).put("y", round4(m.y))
    }

    private fun frameLine(m: Msg.Frame): String = StringBuilder(m.data.length + 96)
        .append("{\"t\":\"frame\",\"n\":").append(m.n).append(",\"w\":").append(m.w).append(",\"h\":").append(m.h)
        .append(",\"iv\":\"").append(m.iv).append("\",\"data\":\"").append(m.data).append("\"}").toString()

    private fun isBase64(s: String): Boolean = s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }

    /** Null for anything that is not one of the messages above (garbage, longer than [max], an unknown key). */
    fun decode(line: String, max: Int = MAX_LINE): Msg? {
        if (line.length > max) return null
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
            "screen" -> Msg.Screen(s("screen"), o.optBoolean("result", false), o.optBoolean("touch", false))
            "save-result" -> Msg.SaveResult
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
            "mirror" -> Msg.Mirror(o.optBoolean("on"), o.optInt("w", 0).coerceIn(0, 8_192))
            "frame" -> o.optString("data", "").takeIf { it.isNotEmpty() }?.let {
                Msg.Frame(o.optInt("n", 0), o.optInt("w", 0).coerceIn(0, 8_192), o.optInt("h", 0).coerceIn(0, 8_192), s("iv"), it)
            }
            "frame-ok" -> Msg.FrameOk(o.optInt("n", -1))
            "frame-none" -> Msg.FrameNone(s("why"))
            "touch" -> {
                val a = runCatching { TouchAction.valueOf(o.optString("a").uppercase()) }.getOrNull() ?: return null
                val x = o.optDouble("x", Double.NaN)
                val y = o.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN()) return null
                Msg.Touch(a, x.coerceIn(0.0, 1.0).toFloat(), y.coerceIn(0.0, 1.0).toFloat())
            }
            else -> null
        }
    }

    /** A move or a scroll is never more than this many dp at once (and never "not a number"). */
    private fun limit(v: Double): Float = if (v.isNaN()) 0f else v.coerceIn(-400.0, 400.0).toFloat()

    private fun round2(v: Float): Double = Math.round(v * 100.0) / 100.0

    /** A touch's place to a ten-thousandth of the page: a fifth of a pixel on the TV's screen. */
    private fun round4(v: Float): Double = Math.round(v * 10_000.0) / 10_000.0

    /** Only devices in the home itself may connect: private, link-local and loopback addresses. */
    fun isHomeAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        // IPv6 unique local addresses (fc00::/7) are the home's own too.
        return a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc
    }
}
