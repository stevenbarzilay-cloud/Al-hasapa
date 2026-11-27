package app.couch.alhasapa.remote

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

/**
 * What the phone-as-keyboard link remembers, in the app's private preferences. On the TV: its own id and the phones that were paired
 * (a random token each, with the phone's name). On the phone: its own id, a token for each TV it was paired with, and where the TV was
 * last found (so the next connection does not have to look for it).
 */
class RemoteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("remote", Context.MODE_PRIVATE)

    private fun id(key: String): String = prefs.getString(key, null) ?: randomHex(6).also { prefs.edit { putString(key, it) } }

    /** This TV's id (the phone keeps its token under it). */
    val tvId: String get() = id("tv_id")

    /** This phone's id. */
    val phoneId: String get() = id("phone_id")

    // ---- on the TV ----

    private fun phones(): JSONObject = runCatching { JSONObject(prefs.getString("phones", "{}")!!) }.getOrElse { JSONObject() }

    /** The name of the paired phone that holds [token], or null. Compared in constant time. */
    fun phoneFor(token: String): String? {
        if (token.length < 32) return null
        val all = phones()
        for (k in all.keys()) {
            if (MessageDigest.isEqual(k.toByteArray(), token.toByteArray())) return all.optString(k)
        }
        return null
    }

    /** The token of the paired phone whose [proof] answers [nonce] (see [LoginCrypto.proof]), or null. */
    fun tokenProvedBy(proof: String, nonce: String, phone: String): String? {
        if (proof.isEmpty() || nonce.isEmpty()) return null
        val all = phones()
        for (k in all.keys()) if (LoginCrypto.same(LoginCrypto.proof(k, nonce, phone), proof)) return k
        return null
    }

    fun addPhone(token: String, name: String) {
        val all = phones().put(token, name.take(60))
        prefs.edit { putString("phones", all.toString()) }
    }

    fun pairedPhones(): List<String> = phones().let { o -> o.keys().asSequence().map { o.optString(it) }.toList() }

    fun forgetPhones() { prefs.edit { remove("phones") } }

    // ---- on the phone ----

    fun tokenFor(tv: String): String = prefs.getString("tv_token_$tv", "") ?: ""

    fun saveToken(tv: String, token: String) { prefs.edit { putString("tv_token_$tv", token) } }

    /** Where the TV was found last time ("host:port"), tried first on the next connection. */
    var lastAddress: String?
        get() = prefs.getString("last_address", null)
        set(value) { prefs.edit { putString("last_address", value) } }

    /** Which of us holds this phone ("a", "b", or one of the others since 1.10), once said (1.9.9); null until then. */
    var me: String?
        get() = prefs.getString("me", null)?.takeIf { app.couch.alhasapa.data.Couple.isId(it) }
        set(value) { prefs.edit { if (value == null) remove("me") else putString("me", value) } }

    /** The newest change to how any of us looks that the TV has been told of (see PhoneSync). */
    var syncedAt: Long
        get() = prefs.getLong("synced_at", 0L)
        set(value) { prefs.edit { putLong("synced_at", value) } }

    /** The notes written on this phone that the TV has not said it keeps yet (they go again at the next connection). */
    fun pendingNotes(): List<app.couch.alhasapa.data.Note> {
        val a = runCatching { org.json.JSONArray(prefs.getString("notes_out", "[]")) }.getOrElse { org.json.JSONArray() }
        return (0 until a.length()).mapNotNull { app.couch.alhasapa.data.Note.fromJson(a.optJSONObject(it)) }
    }

    fun keepNote(n: app.couch.alhasapa.data.Note) = writeNotes((pendingNotes().filter { it.id != n.id } + n).takeLast(MAX_PENDING_NOTES))

    fun noteKept(id: String) = writeNotes(pendingNotes().filter { it.id != id })

    private fun writeNotes(list: List<app.couch.alhasapa.data.Note>) {
        prefs.edit { putString("notes_out", org.json.JSONArray().apply { for (n in list) put(n.toJson()) }.toString()) }
    }

    companion object {
        private val random = SecureRandom()

        /** A phone keeps at most this many notes waiting for the TV (the oldest go first). */
        const val MAX_PENDING_NOTES = 20

        fun randomHex(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** Four digits, shown on the TV and typed on the phone. */
        fun newCode(): String = "%04d".format(random.nextInt(10_000))
    }
}
