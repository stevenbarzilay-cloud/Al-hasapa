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

    companion object {
        private val random = SecureRandom()

        fun randomHex(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** Four digits, shown on the TV and typed on the phone. */
        fun newCode(): String = "%04d".format(random.nextInt(10_000))
    }
}
