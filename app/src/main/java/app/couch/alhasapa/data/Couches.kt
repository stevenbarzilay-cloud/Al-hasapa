package app.couch.alhasapa.data

import java.security.MessageDigest
import org.json.JSONObject

/** A couch as a device names it: an id that never changes, and the name people read. */
data class CouchRef(val id: String, val name: String)

/**
 * A couch (2.0): one home's players, history and points. Until 2.0 there was one couch in the world, ours; now a friend who installs
 * the app has their own, and each of us can be a guest at the other's. Every state document says which couch it is the state of
 * (`couch: {id, name, at}`): the id is made once and travels with the document to the TV and the phones of that home, the name can be
 * changed anywhere (the newer one wins, like a person's name). Nothing of one couch is ever merged into another's document: a share
 * that names another couch is refused whole ([foreign]).
 */
object Couches {
    const val KEY = "couch"
    const val MAX_NAME = 40
    private val ID = Regex("c[0-9a-f]{15}")

    fun isId(s: String?): Boolean = s != null && ID.matches(s)

    fun newId(): String = "c" + java.util.UUID.randomUUID().toString().replace("-", "").take(15)

    /**
     * The id of a couch from before 2.0, on each of its devices without their talking: from the solo relay's topic, which the TV made in
     * 1.11 and handed to its own phones only.
     */
    fun idFromTopic(topic: String): String =
        "c" + MessageDigest.getInstance("SHA-256").digest("couch-id v1 $topic".toByteArray()).joinToString("") { "%02x".format(it) }.take(15)

    /** "הספה של דני ומיה" (or of one of them alone; "הספה שלנו" while nobody was named yet). */
    fun defaultName(a: String, b: String): String {
        val x = a.trim().takeIf { it != CouchState.DEFAULT_A }.orEmpty(); val y = b.trim().takeIf { it != CouchState.DEFAULT_B }.orEmpty()
        return when {
            x.isEmpty() && y.isEmpty() -> "הספה שלנו"
            y.isEmpty() -> "הספה של $x"
            x.isEmpty() -> "הספה של $y"
            else -> "הספה של $x ו$y"
        }
    }

    /** The couch [state] is the state of, or null while it has none (a new device before its first choice, or one from before 2.0). */
    fun of(state: JSONObject): CouchRef? {
        val o = state.optJSONObject(KEY) ?: return null
        val id = o.optString("id").takeIf { isId(it) } ?: return null
        return CouchRef(id, o.optString("name").trim().take(MAX_NAME).ifEmpty { fallbackName(state) })
    }

    /** The name to show for [state]'s couch, whether or not it has an id yet. */
    fun name(state: JSONObject): String = of(state)?.name ?: fallbackName(state)

    private fun fallbackName(state: JSONObject): String = CouchState.names(state).let { (a, b) -> defaultName(a, b) }

    /** When the couch's name was last changed (0: never, it is still the one it was given). */
    fun stamp(state: JSONObject): Long = state.optJSONObject(KEY)?.optLong("at", 0L) ?: 0L

    /** [name] empty: the couch is called after its two players ([defaultName]), and follows their names until someone names it. */
    fun set(state: JSONObject, id: String, name: String, at: Long) {
        state.put(KEY, JSONObject().put("id", id).put("name", name.trim().take(MAX_NAME)).put("at", at.coerceAtLeast(0L)))
    }

    /** The name as it was given ("" while the couch is still called after its players). */
    private fun given(state: JSONObject): String = state.optJSONObject(KEY)?.optString("name").orEmpty().trim().take(MAX_NAME)

    /** A new name, given on this device at [now]. Nothing for a state with no couch, or an empty name. */
    fun rename(state: JSONObject, name: String, now: Long) {
        val cur = of(state) ?: return
        val n = name.trim().take(MAX_NAME)
        if (n.isEmpty() || n == cur.name) return
        set(state, cur.id, n, maxOf(now, stamp(state) + 1))
    }

    /**
     * A new device, nothing chosen on it yet (2.0): no couch, the names nobody gave, nothing played, nobody added. Anything else without
     * a couch is a state from before 2.0, which is given its couch without a question (see CouchSetup).
     */
    fun needsSetup(state: JSONObject): Boolean {
        if (of(state) != null) return false
        val (a, b) = CouchState.names(state)
        return a == CouchState.DEFAULT_A && b == CouchState.DEFAULT_B &&
            (state.optJSONObject("log")?.length() ?: 0) == 0 && (state.optJSONObject("people")?.length() ?: 0) == 0 &&
            (state.optJSONObject("solo")?.length() ?: 0) == 0
    }

    /**
     * A couch with nothing in it yet (2.0): begun on a TV with one press (a TV has no way to type names), never named, nothing played,
     * nobody added, no note. Such a TV joins the couch of the first phone that comes to it as one of its home's own, rather than stand
     * as a couch of its own beside the one that phone began (see KeyServer).
     */
    fun bare(state: JSONObject): Boolean {
        if (of(state) == null || stamp(state) != 0L) return false
        val (a, b) = CouchState.names(state)
        fun none(k: String) = (state.optJSONObject(k)?.length() ?: 0) == 0
        return a == CouchState.DEFAULT_A && b == CouchState.DEFAULT_B && none("log") && none("people") && none("solo") &&
            (state.optJSONArray("notes")?.length() ?: 0) == 0
    }

    /** Whether [share] (another device's, see [Shared]) is of another couch than [state]'s: such a share is never merged. */
    fun foreign(state: JSONObject, share: JSONObject): Boolean {
        val theirs = share.optString("c").ifEmpty { share.optJSONObject(KEY)?.optString("id").orEmpty() }
        val mine = of(state)?.id ?: return false
        return theirs.isNotEmpty() && theirs != mine
    }

    /**
     * What another device of the same home says of the couch: its newer name is kept; a state that has no couch yet takes theirs whole
     * (a new TV joining the couch a phone began, a phone from before 2.0 learning its TV's). Another couch's word changes nothing.
     * True when anything here changed.
     */
    fun offer(state: JSONObject, incoming: JSONObject?): Boolean {
        val id = incoming?.optString("id")?.takeIf { isId(it) } ?: return false
        val name = incoming.optString("name").trim().take(MAX_NAME)
        val at = incoming.optLong("at", 0L).coerceAtLeast(0L)
        val cur = of(state)
        if (cur == null) { set(state, id, name, if (name.isEmpty()) 0L else at); return true }
        if (cur.id != id || name.isEmpty() || at <= stamp(state) || name == given(state)) return false
        set(state, id, name, at)
        return true
    }

    /** As it travels: `{id, name, at}` (the name as it was given), or null for a state with no couch. */
    fun toJson(state: JSONObject): JSONObject? = of(state)?.let { JSONObject().put("id", it.id).put("name", given(state)).put("at", stamp(state)) }

    /**
     * "בספה של דני" for a couch named "הספה של דני"; any other name in quotes ("ב«הסלון»"), so a sentence reads right whatever the couch
     * was called.
     */
    fun inCouch(name: String): String = if (name.startsWith("הספה ")) "ב" + name.drop(1) else "ב«$name»"

    /** "לספה של דני", as [inCouch]. */
    fun toCouch(name: String): String = if (name.startsWith("הספה ")) "ל" + name.drop(1) else "ל«$name»"

    /** A short form of the id for a list ("c3fa…"), enough to tell two couches of the same name apart. */
    fun short(id: String): String = id.drop(1).take(4).uppercase()
}
