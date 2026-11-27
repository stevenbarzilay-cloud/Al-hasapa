package app.couch.alhasapa.remote

import android.content.Context
import androidx.core.content.edit
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.Shared
import app.couch.alhasapa.data.Solo
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.diag.Flight
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** AES-256-GCM boxes for the solo relay (1.11): Base64 of (iv ‖ sealed); [aad] binds a box to what it is for. */
object RelayCrypto {
    private val random = SecureRandom()
    private const val TAG_BITS = 128

    fun seal(key: ByteArray, aad: String, plain: ByteArray): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(aad.toByteArray())
        return Base64.getEncoder().encodeToString(iv + c.doFinal(plain))
    }

    /** The box's bytes, or null for anything wrong (another key or purpose, a changed byte, not Base64, too short). */
    fun open(key: ByteArray, aad: String, box: String): ByteArray? = runCatching {
        val all = Base64.getDecoder().decode(box.trim())
        if (all.size < 12 + 16) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, all, 0, 12))
        c.updateAAD(aad.toByteArray())
        c.doFinal(all, 12, all.size - 12)
    }.getOrNull()
}

/**
 * Where the phones tell each other of the games played alone (1.11): an ntfy server ([base], ntfy.sh unless set otherwise), a topic
 * nobody could guess, and the key every message is sealed with. The TV makes it once and hands it to each paired phone, sealed under
 * that connection's key (KeyLink `relay`). The server sees that messages come and go on a topic, never what they say.
 */
class RelayConfig(val base: String, val topic: String, val key: ByteArray) {
    fun toJson(): String = JSONObject().put("base", base).put("topic", topic).put("key", Base64.getEncoder().encodeToString(key)).toString()

    override fun equals(other: Any?): Boolean = other is RelayConfig && other.base == base && other.topic == topic && other.key.contentEquals(key)
    override fun hashCode(): Int = topic.hashCode()

    companion object {
        const val DEFAULT_BASE = "https://ntfy.sh"
        private val TOPIC = Regex("[A-Za-z0-9_-]{16,64}")
        private val BASE = Regex("https://[a-z0-9.-]+(:[0-9]{1,5})?|http://(127\\.0\\.0\\.1|localhost)(:[0-9]{1,5})?")
        private val random = SecureRandom()

        fun create(base: String = DEFAULT_BASE): RelayConfig {
            val t = ByteArray(16).also { random.nextBytes(it) }
            val k = ByteArray(32).also { random.nextBytes(it) }
            return RelayConfig(base, "couch-" + Base64.getUrlEncoder().withoutPadding().encodeToString(t), k)
        }

        /** Null for anything that is not a whole, sensible configuration (an https server, a long topic, a 256-bit key). */
        fun parse(json: String?): RelayConfig? = runCatching {
            val o = JSONObject(json ?: return null)
            val base = o.getString("base").trimEnd('/')
            val topic = o.getString("topic")
            val key = Base64.getDecoder().decode(o.getString("key"))
            if (!BASE.matches(base) || !TOPIC.matches(topic) || key.size != 32) null else RelayConfig(base, topic, key)
        }.getOrNull()
    }
}

/** The relay's HTTP: publish a message, read the ones since a point. Replaceable in tests. */
interface RelayHttp {
    /** POSTs [body]; the HTTP status, or -1 when the server could not be reached. */
    fun post(url: String, body: String): Int

    /** GETs; the body, or null when the server could not be reached or answered with an error. */
    fun get(url: String): String?
}

/** Plain HttpURLConnection, with the platform's own client identity. */
object UrlRelayHttp : RelayHttp {
    private const val CONNECT_MS = 8_000
    private const val READ_MS = 12_000
    private const val MAX_BODY = 256 * 1024

    override fun post(url: String, body: String): Int = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = CONNECT_MS; c.readTimeout = READ_MS
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
            c.responseCode
        } finally { c.disconnect() }
    }.getOrDefault(-1)

    override fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = CONNECT_MS; c.readTimeout = READ_MS
            if (c.responseCode !in 200..299) return null
            // (InputStream.readNBytes is Android 13's; the TV runs Android 11.)
            c.inputStream.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() < MAX_BODY) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, minOf(n, MAX_BODY - out.size())) }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        } finally { c.disconnect() }
    }.getOrNull()
}

/** The ntfy side of the relay: publish one message to the topic, and read the messages since a point (an id, or "12h"). */
class RelayClient(private val config: RelayConfig, private val http: RelayHttp = UrlRelayHttp) {
    data class Message(val id: String, val time: Long, val text: String)

    fun publish(text: String): Boolean = http.post("${config.base}/${config.topic}", text) in 200..299

    /** The messages since [since], oldest first, or null when the relay could not be read. */
    fun poll(since: String): List<Message>? {
        val body = http.get("${config.base}/${config.topic}/json?poll=1&since=${URLEncoder.encode(since, "UTF-8")}") ?: return null
        return body.lineSequence().mapNotNull { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
            if (o.optString("event") != "message") return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotEmpty() && it.length <= 64 } ?: return@mapNotNull null
            Message(id, o.optLong("time", 0L), o.optString("message"))
        }.toList()
    }
}

/** What the relay keeps on this device: its configuration, how far it has read, what it last sent, and what it has told. */
class RelayStore(context: Context, name: String = "relay") {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    var config: RelayConfig?
        get() = RelayConfig.parse(prefs.getString("config", null))
        set(v) = prefs.edit { if (v == null) remove("config") else putString("config", v.toJson()) }

    /** The TV's own configuration, made the first time a phone is to be given one. */
    fun ensure(): RelayConfig = config ?: RelayConfig.create().also { config = it; Flight.log("relay: a new topic and key for the phones") }

    /** When a phone without the relay last went to the TV for it (once a day at most: an older TV has none to give). */
    var askedAt: Long
        get() = prefs.getLong("asked_at", 0L)
        set(v) = prefs.edit { putLong("asked_at", v) }

    /** Whether the phone was asked if the app may say things with the app closed (once, the first time a game is played alone). */
    var notifyAsked: Boolean
        get() = prefs.getBoolean("notify_asked", false)
        set(v) = prefs.edit { putBoolean("notify_asked", v) }

    /** What this device changed was last said through the relay up to this time (1.12), and whether a change waits to be said. */
    var shareSentAt: Long
        get() = prefs.getLong("share_sent_at", 0L)
        set(v) = prefs.edit { putLong("share_sent_at", v) }
    var sharePending: Boolean
        get() = prefs.getBoolean("share_pending", false)
        set(v) = prefs.edit { putBoolean("share_pending", v) }

    /** The home couch was changed (2.0): the channel of the home before is let go of whole; the new home's TV hands over its own. */
    fun forgetChannel() {
        prefs.edit { for (k in listOf("config", "last_id", "sent_at", "pending", "share_sent_at", "share_pending", "asked_at", "told")) remove(k) }
    }

    /** The id of the last message read (null: never read). */
    var lastId: String?
        get() = prefs.getString("last_id", null)
        set(v) = prefs.edit { putString("last_id", v) }

    /** When this phone's sides last reached the relay, and whether a change since waits to go. */
    var sentAt: Long
        get() = prefs.getLong("sent_at", 0L)
        set(v) = prefs.edit { putLong("sent_at", v) }
    var pending: Boolean
        get() = prefs.getBoolean("pending", false)
        set(v) = prefs.edit { putBoolean("pending", v) }

    /** What this phone has already said ("day|game|who|kind"), so nothing is said twice. Only the last days are kept. */
    fun told(key: String): Boolean = prefs.getStringSet("told", emptySet())!!.contains(key)
    fun tell(key: String, keepFrom: String) {
        val now = prefs.getStringSet("told", emptySet())!!.filter { it.substringBefore('|') >= keepFrom }.toMutableSet().apply { add(key) }
        prefs.edit { putStringSet("told", now) }
    }

    /** Together or alone, chosen on this phone for a game on a day ("day|game" -> "solo" / "together"); only the last days are kept. */
    fun mode(day: String, game: String): String? = prefs.getString("mode|$day|$game", null)
    fun setMode(day: String, game: String, mode: String) {
        prefs.edit {
            for (k in prefs.all.keys) if (k.startsWith("mode|") && k.split('|').getOrNull(1).orEmpty() < day) remove(k)
            putString("mode|$day|$game", mode)
        }
    }
}

/**
 * Solo games travel between the phones, and to the TV, through the relay (1.11). Each phone publishes the sides of whoever holds it,
 * of today and yesterday, as one sealed message (the whole of them each time: a message lost is made good by the next); every device
 * reads the topic since where it stopped and keeps the newer of each side. The relay keeps a message for half a day, so a phone with
 * a side of today says it again when it last did so more than [RESEND_MS] ago.
 *
 * Read and sent on a background thread: on the app's start, after a game played alone, and from the background job (SoloJob).
 */
class SoloRelay(
    context: Context,
    private val repo: StateRepository,
    private val http: RelayHttp = UrlRelayHttp,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    private val store: RelayStore = RelayStore(context),
) {

    /** Something another person did alone that this device has not said yet: they played ([reveal] false), or both did (true). */
    data class News(val day: String, val game: String, val who: String, val reveal: Boolean)

    val ready: Boolean get() = store.config != null

    private fun days(): List<String> = listOf(CouchState.dayKey(today()), CouchState.dayKey(today().minusDays(1)))

    private fun aad(c: RelayConfig) = "couch-solo v1 ${c.topic}"

    /** Publishes [me]'s sides of today and yesterday. True when the relay took them (or there was nothing to say). */
    fun send(me: String): Boolean {
        val c = store.config ?: return false
        val mine = Solo.mine(repo.snapshot(), me, days())
        if (mine.length() == 0) { store.pending = false; return true }
        val plain = JSONObject().put("v", 1).put("who", me).put("solo", mine).toString().toByteArray()
        val ok = RelayClient(c, http).publish(RelayCrypto.seal(c.key, aad(c), plain))
        store.pending = !ok
        if (ok) store.sentAt = now()
        Flight.log(if (ok) "solo: told the relay" else "solo: the relay was not reached; it will be told later")
        return ok
    }

    /**
     * Reads what came since the last time and keeps it. What is new for [me] (null on the TV, which tells nobody): another person's
     * side of today, in a game [me] has played alone too (a reveal) or not (a challenge), each said once. Null when the relay could not
     * be read.
     */
    fun receive(me: String?): List<News>? {
        val c = store.config ?: return null
        val msgs = RelayClient(c, http).poll(store.lastId ?: "12h") ?: return null
        val before = repo.snapshot()
        val incoming = JSONObject()
        for (m in msgs) {
            val plain = RelayCrypto.open(c.key, aad(c), m.text) ?: continue
            val o = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: continue
            if (o.optInt("v") != 1) continue
            val who = o.optString("who")
            val solo = o.optJSONObject("solo") ?: continue
            // Only the sender's own sides: one phone never writes another person's game.
            for (day in solo.keys().asSequence().toList()) {
                val d = solo.optJSONObject(day) ?: continue
                for (game in d.keys().asSequence().toList()) {
                    val side = d.optJSONObject(game)?.optJSONObject(who) ?: continue
                    val into = incoming.optJSONObject(day) ?: JSONObject().also { incoming.put(day, it) }
                    val g = into.optJSONObject(game) ?: JSONObject().also { into.put(game, it) }
                    val cur = g.optJSONObject(who)
                    if (cur == null || cur.optLong("t") < side.optLong("t")) g.put(who, side)
                }
            }
        }
        // What another device kept alike changed (1.12): merged message by message, the newer of each thing winning.
        var shared = 0
        for (m in msgs) {
            val o = RelayCrypto.open(c.key, aad(c), m.text)?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: continue
            o.optJSONObject("share")?.let { if (repo.mergeShared(it)) shared++ }
        }
        if (shared > 0) Flight.log("sync: the others' changes from the relay ($shared)")
        msgs.lastOrNull()?.let { store.lastId = it.id }
        if (incoming.length() > 0) {
            var changed = false
            changed = repo.mergeIf { s -> Solo.merge(s, incoming, now()) }
            if (changed) Flight.log("solo: news from the relay")
        }
        if (me == null) return emptyList()
        val day = days().first()
        val after = repo.snapshot()
        val news = ArrayList<News>()
        for (game in Solo.games(after, day)) {
            val mine = Solo.side(after, day, game, me)
            for (s in Solo.sides(after, day, game)) {
                // (Someone who left the contest is no news.)
                if (s.who == me || s.out) continue
                val was = Solo.side(before, day, game, s.who)
                if (was != null && was.t >= s.t) continue                       // nothing new from them
                // Their result, new, when this phone's person has one too: the reveal (once; a later mend of it is no news).
                val reveal = mine?.hasResult == true && s.hasResult && was?.hasResult != true
                if (mine != null && !reveal) continue                            // played here too: only the reveal is news
                val key = "$day|$game|${s.who}|${if (reveal) "reveal" else "played"}"
                if (store.told(key)) continue
                store.tell(key, days().last())
                news += News(day, game, s.who, reveal)
            }
        }
        return news
    }

    /**
     * What this device changed since it last said so (1.12): the log of today and yesterday with its removals, and the library, links,
     * our games, jokes and looks that changed, without the texts results were read from, in parts each well within the relay's limit
     * for a message. [from] names the sender ("a", "b", "tv"...). True when the relay took it all (or there was nothing to say).
     */
    fun sendShare(from: String): Boolean {
        val c = store.config ?: return false
        val upTo = now()
        val since = store.shareSentAt.takeIf { it > 0 } ?: (upTo - FIRST_SHARE_MS)
        val share = Shared.share(repo.snapshot(), days(), since = since, raw = false, rest = true)
        val parts = parts(share)
        if (parts.isEmpty()) { store.shareSentAt = upTo; store.sharePending = false; return true }
        val client = RelayClient(c, http)
        for (p in parts) {
            val plain = JSONObject().put("v", 1).put("who", from).put("share", p).toString().toByteArray()
            if (!client.publish(RelayCrypto.seal(c.key, aad(c), plain))) {
                store.sharePending = true
                Flight.log("sync: the relay was not reached; the changes wait")
                return false
            }
        }
        store.shareSentAt = upTo; store.sharePending = false
        Flight.log("sync: told the relay of the changes (${parts.size})")
        return true
    }

    /** Sends what waits (or what the relay may have let go of), then reads. The news for [me]. */
    fun sync(me: String?): List<News> {
        if (store.config == null) return emptyList()
        if (store.sharePending) sendShare(me ?: "tv")
        if (me != null) {
            val day = days().first()
            val hasToday = Solo.games(repo.snapshot(), day).any { Solo.side(repo.snapshot(), day, it, me) != null }
            if (store.pending || (hasToday && now() - store.sentAt > RESEND_MS)) send(me)
        }
        return receive(me).orEmpty()
    }

    companion object {
        /** A side of today is said again after this long (the relay keeps a message for twelve hours). */
        const val RESEND_MS = 6 * 60 * 60 * 1000L
        /** The first time a device says its changes: those of the last day. */
        const val FIRST_SHARE_MS = 24 * 60 * 60 * 1000L
        /** A message of the relay is at most 4096 bytes; a part this big, sealed and in Base64, stays under it. */
        const val MAX_PART = 2_600

        private fun size(o: JSONObject) = o.toString().toByteArray().size

        /**
         * [share] in parts small enough for the relay: as it is when it fits; else its evenings entry by entry, then each of the rest
         * on its own (something that alone is too big, a very long list of jokes say, is left to the home network).
         */
        fun parts(share: JSONObject): List<JSONObject> {
            val keys = share.keys().asSequence().filter { it != "v" && it != "c" }.toList()
            if (keys.isEmpty()) return emptyList()
            if (size(share) <= MAX_PART) return listOf(share)
            val out = ArrayList<JSONObject>()
            var cur = Shared.blank(share)
            fun flush() { if (!Shared.empty(cur)) out += cur; cur = Shared.blank(share) }
            share.optJSONObject("log")?.let { log ->
                for (day in log.keys().asSequence().toList()) {
                    val d = log.optJSONObject(day) ?: continue
                    for (id in d.keys().asSequence().toList()) {
                        val e = d.optJSONObject(id) ?: continue
                        val one = Shared.blank(share).put("log", JSONObject().put(day, JSONObject().put(id, e)))
                        if (size(one) > MAX_PART) continue
                        val l = cur.optJSONObject("log") ?: JSONObject().also { cur.put("log", it) }
                        val dd = l.optJSONObject(day) ?: JSONObject().also { l.put(day, it) }
                        dd.put(id, e)
                        if (size(cur) > MAX_PART) { dd.remove(id); if (dd.length() == 0) l.remove(day); flush(); cur.put("log", JSONObject().put(day, JSONObject().put(id, e))) }
                    }
                }
            }
            flush()
            for (k in keys) {
                if (k == "log") continue
                val one = Shared.blank(share).put(k, share.get(k))
                // A stamp travels with what it stamps: the library's and the links' stamps go with them.
                if (k == "prefs" || k == "urls" || k == "custom" || k == "jokes") continue
                if (k == Shared.STAMPS) {
                    val all = share.optJSONObject(Shared.STAMPS) ?: continue
                    for (name in listOf("prefs", "urls", "custom", "jokes")) {
                        val st = JSONObject().apply { for (s in all.keys().asSequence().filter { it == name || it.startsWith("$name|") }) put(s, all.optLong(s)) }
                        if (st.length() == 0) continue
                        val part = Shared.blank(share).put(Shared.STAMPS, st).apply { if (share.has(name)) put(name, share.get(name)) }
                        if (size(part) <= MAX_PART) out += part
                    }
                    continue
                }
                if (size(one) <= MAX_PART) out += one
            }
            return out
        }
    }
}
