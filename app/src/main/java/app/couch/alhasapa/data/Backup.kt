package app.couch.alhasapa.data

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * The backup format is the prototype's ("להעתיק גיבוי"): {"app":"al-hasapa-shelanu","exported":ISO,"state":{...}}.
 * Importing validates it and MERGES it into what is already there: per day and game the newer entry wins (entries carry `t`),
 * nothing is ever deleted, and a bare state document (no wrapper) is accepted too.
 */
object Backup {
    const val APP_ID = "al-hasapa-shelanu"
    private const val MAX_CHARS = 5_000_000

    fun export(state: JSONObject, now: Instant): String =
        JSONObject().put("app", APP_ID).put("exported", now.toString()).put("state", state).toString()

    sealed interface Parsed {
        data class Ok(val state: JSONObject, val skippedEntries: Int) : Parsed
        data class Bad(val reason: Reason) : Parsed
    }

    enum class Reason { EMPTY, TOO_BIG, NOT_JSON, NOT_OURS, WRONG_VERSION, NO_HISTORY_OBJECT }

    data class Stats(val days: Int, val added: Int, val updated: Int, val kept: Int, val skipped: Int)
    data class Merged(val state: JSONObject, val stats: Stats)

    sealed interface Outcome {
        data class Merged(val stats: Stats, val revision: Long) : Outcome
        data class Rejected(val reason: Reason) : Outcome
    }

    fun parse(text: String): Parsed {
        val t = text.trim()
        if (t.isEmpty()) return Parsed.Bad(Reason.EMPTY)
        if (t.length > MAX_CHARS) return Parsed.Bad(Reason.TOO_BIG)
        val root = runCatching { JSONObject(t) }.getOrNull() ?: return Parsed.Bad(Reason.NOT_JSON)
        val stateObj = when {
            root.has("state") && root.optJSONObject("state") != null -> {
                if (root.optString("app") != APP_ID) return Parsed.Bad(Reason.NOT_OURS)
                root.getJSONObject("state")
            }
            root.has("log") && root.has("v") -> root   // a bare state document
            else -> return Parsed.Bad(Reason.NOT_OURS)
        }
        if (stateObj.optInt("v", -1) != CouchState.VERSION) return Parsed.Bad(Reason.WRONG_VERSION)
        val log = stateObj.optJSONObject("log") ?: return Parsed.Bad(Reason.NO_HISTORY_OBJECT)
        // Keep only what is well-formed; count what is dropped.
        var skipped = 0
        val clean = JSONObject()
        for (day in log.keys()) {
            val d = log.optJSONObject(day)
            if (!CouchState.isDayKey(day) || d == null) { skipped++; continue }
            val cd = JSONObject()
            for (id in d.keys()) {
                val e = d.optJSONObject(id)
                if (e == null) { skipped++; continue }
                cd.put(id, e)
            }
            if (cd.length() > 0) clean.put(day, cd)
        }
        val copy = CouchState.copy(stateObj).put("log", clean)
        val normalized = CouchState.normalize(copy) ?: return Parsed.Bad(Reason.WRONG_VERSION)
        return Parsed.Ok(normalized, skipped)
    }

    fun merge(local: JSONObject, incoming: JSONObject): Merged {
        var added = 0; var updated = 0; var kept = 0
        val localLog = local.optJSONObject("log") ?: JSONObject().also { local.put("log", it) }
        val inLog = incoming.optJSONObject("log") ?: JSONObject()
        for (day in inLog.keys()) {
            val inDay = inLog.getJSONObject(day)
            val ld = localLog.optJSONObject(day) ?: JSONObject().also { localLog.put(day, it) }
            for (id in inDay.keys()) {
                val theirs = inDay.getJSONObject(id)
                val mine = ld.optJSONObject(id)
                if (mine == null) { ld.put(id, theirs); added++; continue }
                val picked = pick(mine, theirs)
                if (picked.toString() == mine.toString()) kept++ else { ld.put(id, picked); updated++ }
            }
        }
        // names: a name someone changed beats the default; otherwise the local one stays
        val ln = local.optJSONObject("names") ?: JSONObject().also { local.put("names", it) }
        val inn = incoming.optJSONObject("names")
        if (inn != null) {
            if (ln.optString("a") == CouchState.DEFAULT_A && inn.optString("a").isNotBlank()) ln.put("a", inn.optString("a"))
            if (ln.optString("b") == CouchState.DEFAULT_B && inn.optString("b").isNotBlank()) ln.put("b", inn.optString("b"))
        }
        // jokes: union, local order first
        val jokes = LinkedHashSet<String>()
        local.optJSONArray("jokes")?.let { a -> for (i in 0 until a.length()) jokes += a.optString(i) }
        incoming.optJSONArray("jokes")?.let { a -> for (i in 0 until a.length()) jokes += a.optString(i) }
        if (jokes.isNotEmpty()) local.put("jokes", JSONArray(jokes.filter { it.isNotBlank() }.take(60)))
        // prefs / urls: add what is missing, never override a choice made here
        mergeMissing(local, incoming, "prefs")
        mergeMissing(local, incoming, "urls")
        // pieces: keep this phone's choice if it has one
        if (!local.has("pieces") && incoming.has("pieces")) local.put("pieces", incoming.get("pieces"))
        // anything else the other side knows (a newer app's fields) is kept if we have nothing under that name
        for (k in incoming.keys()) if (!local.has(k)) local.put(k, incoming.get(k))
        return Merged(local, Stats(inLog.length(), added, updated, kept, 0))
    }

    private fun mergeMissing(local: JSONObject, incoming: JSONObject, key: String) {
        val inc = incoming.optJSONObject(key) ?: return
        val loc = local.optJSONObject(key) ?: JSONObject().also { local.put(key, it) }
        for (k in inc.keys()) if (!loc.has(k)) loc.put(k, inc.get(k))
    }

    private fun stamp(o: JSONObject?): Long = o?.optLong("t", 0L) ?: 0L

    private fun richness(e: JSONObject): Int =
        (if (e.has("d")) 4 else 0) + (if (e.optJSONObject("r") != null) 2 else 0) + (if (e.optString("w").isNotEmpty()) 1 else 0)

    /** The newer of two entries for the same day and game. Head-to-head sides are merged side by side. */
    internal fun pick(mine: JSONObject, theirs: JSONObject): JSONObject {
        if (mine.has("d") && theirs.has("d")) {
            val d = JSONObject()
            for (side in listOf("a", "b")) {
                val m = mine.optJSONObject("d")?.optJSONObject(side)
                val o = theirs.optJSONObject("d")?.optJSONObject(side)
                val chosen = when {
                    m == null -> o
                    o == null -> m
                    stamp(o) > stamp(m) -> o
                    else -> m
                }
                if (chosen != null) d.put(side, chosen)
            }
            val winner = if (stamp(theirs) > stamp(mine)) theirs else mine
            return CouchState.copy(winner).put("d", d).apply { put("t", maxOf(stamp(mine), stamp(theirs))) }.also { if (it.optLong("t") == 0L) it.remove("t") }
        }
        val ts = stamp(theirs)
        val ms = stamp(mine)
        return when {
            ts > ms -> theirs
            ts < ms -> mine
            richness(theirs) > richness(mine) -> theirs   // same age: the one that knows more
            else -> mine
        }
    }
}
