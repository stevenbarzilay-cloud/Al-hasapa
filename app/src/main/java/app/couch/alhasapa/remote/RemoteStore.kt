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
class RemoteStore(context: Context, name: String = "remote") {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

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

    /**
     * The token of the paired phone whose [proof] answers [nonce] (see [LoginCrypto.proof]), or null. A guest of another evening is
     * a guest no longer (2.0): their token is let go of here.
     */
    fun tokenProvedBy(proof: String, nonce: String, phone: String, evening: String = app.couch.alhasapa.data.Couple.evening()): String? {
        if (proof.isEmpty() || nonce.isEmpty()) return null
        forgetPastGuests(evening)
        val all = phones()
        for (k in all.keys()) if (LoginCrypto.same(LoginCrypto.proof(k, nonce, phone), proof)) return k
        return null
    }

    /**
     * A phone was paired, as [role] (2.0: one of the home's own, a friend who may come again, or a friend for [evening]). A phone
     * paired before 2.0 has no role written, and is of the home, as it always was.
     */
    fun addPhone(token: String, name: String, role: String = KeyLink.AS_HOME, evening: String? = null) {
        val all = phones().put(token, name.take(60))
        val r = roles()
        if (role == KeyLink.AS_HOME) r.remove(token)
        else r.put(token, JSONObject().put("r", role).apply { if (role == KeyLink.AS_EVENING && evening != null) put("day", evening) })
        prefs.edit { putString("phones", all.toString()); putString("roles", r.toString()) }
    }

    private fun roles(): JSONObject = runCatching { JSONObject(prefs.getString("roles", "{}")!!) }.getOrElse { JSONObject() }

    /** What the phone that holds [token] was paired as. */
    fun roleOf(token: String): String = roles().optJSONObject(token)?.optString("r")?.takeIf { it in KeyLink.ROLES } ?: KeyLink.AS_HOME

    /** The people a guest's phone added here (at most a few: itself, as a rule), by its token. */
    fun madeBy(token: String): Set<String> =
        roles().optJSONObject(token)?.optJSONArray("made")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()

    fun addMade(token: String, who: String) {
        val r = roles()
        val o = r.optJSONObject(token) ?: return
        val a = o.optJSONArray("made") ?: org.json.JSONArray().also { o.put("made", it) }
        if ((0 until a.length()).none { a.optString(it) == who }) a.put(who)
        prefs.edit { putString("roles", r.toString()) }
    }

    fun forgetPhone(token: String) {
        val all = phones().apply { remove(token) }
        val r = roles().apply { remove(token) }
        prefs.edit { putString("phones", all.toString()); putString("roles", r.toString()) }
    }

    private fun forgetPastGuests(evening: String) {
        val r = roles()
        val past = r.keys().asSequence().filter { k -> r.optJSONObject(k)?.let { it.optString("r") == KeyLink.AS_EVENING && it.optString("day") != evening } == true }.toList()
        if (past.isEmpty()) return
        val all = phones()
        for (k in past) { all.remove(k); r.remove(k) }
        prefs.edit { putString("phones", all.toString()); putString("roles", r.toString()) }
    }

    /** The paired phones by name: the home's own, and the friends who may come again or are here this evening. */
    fun pairedPhones(): List<String> = phones().let { o -> o.keys().asSequence().map { o.optString(it) }.toList() }

    /** How many phones are paired as the home's own (from before 2.0, every paired phone). */
    fun homePhones(): Int = phones().keys().asSequence().count { roleOf(it) == KeyLink.AS_HOME }

    fun pairedGuests(): List<String> = phones().let { o -> o.keys().asSequence().filter { roleOf(it) != KeyLink.AS_HOME }.map { o.optString(it) }.toList() }

    fun forgetPhones() { prefs.edit { remove("phones"); remove("roles") } }

    /** The friends' phones are forgotten (the home's own stay): each needs the code again to come back. */
    fun forgetGuests() {
        val all = phones()
        for (k in all.keys().asSequence().toList()) if (roleOf(k) != KeyLink.AS_HOME) all.remove(k)
        prefs.edit { putString("phones", all.toString()); remove("roles") }
    }

    // ---- on the phone ----

    fun tokenFor(tv: String): String = prefs.getString("tv_token_$tv", "") ?: ""

    fun saveToken(tv: String, token: String) { prefs.edit { putString("tv_token_$tv", token) } }

    /** The TVs this phone holds a pairing token for (2.0: each belongs to a couch, see CouchStore). */
    fun knownTvs(): List<String> = prefs.all.keys.filter { it.startsWith("tv_token_") && !prefs.getString(it, "").isNullOrEmpty() }.map { it.removePrefix("tv_token_") }.sorted()

    /** The pairing with [tv] is let go of here (the TV keeps its side until it is forgotten there). */
    fun forgetTv(tv: String) { prefs.edit { remove("tv_token_$tv"); remove("addr_$tv"); remove("shared_all_$tv") } }

    /** Where the TV was found last time ("host:port"), tried first on the next connection. From before 2.0: whichever TV it was. */
    var lastAddress: String?
        get() = prefs.getString("last_address", null)
        set(value) { prefs.edit { putString("last_address", value) } }

    /** Where the TV [tv] was found last time (2.0: each TV its own, so a friend's TV is never tried in place of the home's). */
    fun addressOf(tv: String): String? = prefs.getString("addr_$tv", null)
    fun setAddress(tv: String, address: String) { prefs.edit { putString("addr_$tv", address) } }

    /** Which of us holds this phone ("a", "b", or one of the others since 1.10), once said (1.9.9); null until then. */
    var me: String?
        get() = prefs.getString("me", null)?.takeIf { app.couch.alhasapa.data.Couple.isId(it) }
        set(value) { prefs.edit { if (value == null) remove("me") else putString("me", value) } }

    /** The newest change to how any of us looks that the TV has been told of (see PhoneSync). */
    /** Whether this phone and the TV [tv] have told each other of every evening they keep (once, 1.12; two weeks of them after that). */
    fun sharedAll(tv: String): Boolean = prefs.getBoolean("shared_all_$tv", false)
    fun setSharedAll(tv: String) { prefs.edit { putBoolean("shared_all_$tv", true) } }
    fun clearSharedAll(tv: String) { prefs.edit { remove("shared_all_$tv") } }

    /** When this phone and the TV last told each other what they keep alike (1.12, as a connection opened). */
    var sharedAt: Long
        get() = prefs.getLong("shared_at", 0L)
        set(v) = prefs.edit { putLong("shared_at", v) }

    var syncedAt: Long
        get() = prefs.getLong("synced_at", 0L)
        set(value) { prefs.edit { putLong("synced_at", value) } }

    /**
     * The notes written on this phone that the TV has not said it keeps yet (they go again at the next connection). Each waits for
     * the TV of the couch it was written for (2.0): a note for the home never goes to a friend's TV, nor the other way.
     */
    private fun notesRaw(): List<org.json.JSONObject> {
        val a = runCatching { org.json.JSONArray(prefs.getString("notes_out", "[]")) }.getOrElse { org.json.JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { app.couch.alhasapa.data.Note.fromJson(it) != null }
    }

    /** All of them, whichever couch they wait for. */
    fun pendingNotes(): List<app.couch.alhasapa.data.Note> = notesRaw().mapNotNull { app.couch.alhasapa.data.Note.fromJson(it) }

    /** Those waiting for the couch [couch]; one written before 2.0 says no couch, and is the [home]'s. */
    fun pendingNotes(couch: String?, home: String?): List<app.couch.alhasapa.data.Note> = notesRaw()
        .filter { o -> o.optString("couch").let { c -> if (c.isEmpty()) couch == home else c == couch } }
        .mapNotNull { app.couch.alhasapa.data.Note.fromJson(it) }

    fun keepNote(n: app.couch.alhasapa.data.Note, couch: String? = null) =
        writeNotes((notesRaw().filter { it.optString("id") != n.id } + n.toJson().apply { if (couch != null) put("couch", couch) }).takeLast(MAX_PENDING_NOTES))

    fun noteKept(id: String) = writeNotes(notesRaw().filter { it.optString("id") != id })

    private fun writeNotes(list: List<org.json.JSONObject>) {
        prefs.edit { putString("notes_out", org.json.JSONArray().apply { for (n in list) put(n) }.toString()) }
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
