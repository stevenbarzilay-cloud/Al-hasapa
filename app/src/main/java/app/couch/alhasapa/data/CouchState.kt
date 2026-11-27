package app.couch.alhasapa.data

import java.time.LocalDate
import org.json.JSONObject

/**
 * The whole app state is ONE JSON document, the prototype's schema plus additive fields:
 *   v, names{a,b}, prefs{gameId: yes|no|later}, jokes[], log{YYYY-MM-DD: {gameId: {w, r?, d?, t?}}}
 *   + pieces{a:{shape,color}, b:{...}}  (shape and colour per person)
 *   + urls{gameId: url}                (the person's own links, overriding the catalogue's)
 *   + couch{id, name, at}              (2.0: which couch this is the state of, see [Couches])
 * Entries carry `t` (epoch ms of the last change) so two phones' histories can be merged "newest wins".
 * Unknown fields are always preserved, so a newer app's backup never loses data in an older one.
 */
object CouchState {
    const val VERSION = 1
    /** The names of a couch nobody has named yet (2.0): a friend's new couch must not open as ours. */
    const val DEFAULT_A = "שחקן 1"
    const val DEFAULT_B = "שחקן 2"
    /** Until 2.0 there was one couch, ours: a state from then (it has no couch record) whose names were left empty keeps them. */
    private const val LEGACY_A = "סטיב"
    private const val LEGACY_B = "רוני"
    const val KEEP_DAYS = 400L
    private val DAY_KEY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    fun empty(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("names", JSONObject().put("a", DEFAULT_A).put("b", DEFAULT_B))
        .put("prefs", JSONObject())
        .put("log", JSONObject())

    fun isDayKey(s: String) = DAY_KEY.matches(s)

    fun dayKey(d: LocalDate): String = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)

    /** The state if [raw] is a valid state document, else null. Missing top-level containers are filled in. */
    fun parse(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return normalize(o)
    }

    fun normalize(o: JSONObject): JSONObject? {
        if (o.optInt("v", -1) != VERSION) return null
        val legacy = o.optJSONObject(Couches.KEY) == null
        val a = if (legacy) LEGACY_A else DEFAULT_A
        val b = if (legacy) LEGACY_B else DEFAULT_B
        if (o.optJSONObject("names") == null) o.put("names", JSONObject().put("a", a).put("b", b))
        o.optJSONObject("names")!!.apply {
            if (optString("a").isBlank()) put("a", a)
            if (optString("b").isBlank()) put("b", b)
        }
        if (o.optJSONObject("prefs") == null) o.put("prefs", JSONObject())
        if (o.optJSONObject("log") == null) o.put("log", JSONObject())
        return o
    }

    /** Drops history older than 400 days, as the prototype does on every save. */
    fun prune(state: JSONObject, today: LocalDate) {
        val cutoff = dayKey(today.minusDays(KEEP_DAYS))
        val log = state.optJSONObject("log") ?: return
        for (k in log.keys().asSequence().toList()) if (k < cutoff) log.remove(k)
    }

    fun names(state: JSONObject): Pair<String, String> {
        val n = state.optJSONObject("names")
        return (n?.optString("a")?.ifBlank { DEFAULT_A } ?: DEFAULT_A) to (n?.optString("b")?.ifBlank { DEFAULT_B } ?: DEFAULT_B)
    }

    fun entry(state: JSONObject, day: String, gameId: String): JSONObject? =
        state.optJSONObject("log")?.optJSONObject(day)?.optJSONObject(gameId)

    fun setEntry(state: JSONObject, day: String, gameId: String, entry: JSONObject?) {
        val log = state.optJSONObject("log") ?: JSONObject().also { state.put("log", it) }
        val d = log.optJSONObject(day) ?: JSONObject().also { log.put(day, it) }
        if (entry == null) {
            d.remove(gameId)
            if (d.length() == 0) log.remove(day)
        } else {
            d.put(gameId, entry)
        }
    }

    /** The URL the person set for this game, if any. */
    fun urlOverride(state: JSONObject, gameId: String): String? =
        state.optJSONObject("urls")?.optString(gameId)?.takeIf { it.isNotBlank() }

    fun copy(o: JSONObject): JSONObject = JSONObject(o.toString())
}
