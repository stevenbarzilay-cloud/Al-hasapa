package app.couch.alhasapa.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the TV and the phones keep alike (1.12): the evenings' log (what we played, the results, who cracked it, so the couch points
 * are the same everywhere), the library (which games are on our shelf), our own links, our own games, our jokes, how each of us looks,
 * and the games played alone. Every device keeps its own copy; where two copies meet (a phone connecting to the TV at home, or a
 * message through the relay from anywhere), the newer of each thing wins.
 *
 * "Newer" needs a time on everything: a log entry carries its own (`t`, as always); a removed entry leaves a mark of when it went
 * ([GONE]: "day|game" -> t), so another device's older copy does not bring it back; the library, the links, our games and the jokes
 * are stamped key by key ([STAMPS]: "prefs|game", "urls|game", "custom|game", "jokes" -> t). The stamps are set by [stampChanges] for
 * every change made on this device, wherever it was made (the hub's page, the player, the settings), so nothing else needs to know.
 */
object Shared {
    const val GONE = "gone"
    const val STAMPS = "stamps"
    private val KEYED = listOf("prefs", "urls")

    private fun obj(o: JSONObject, k: String): JSONObject = o.optJSONObject(k) ?: JSONObject().also { o.put(k, it) }
    private fun t(e: JSONObject?): Long = e?.optLong("t", 0L) ?: 0L
    private fun same(a: Any?, b: Any?): Boolean = (a?.toString() ?: "") == (b?.toString() ?: "")

    private fun customById(s: JSONObject): Map<String, JSONObject> {
        val a = s.optJSONArray("custom") ?: return emptyMap()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").isNotBlank() }.associateBy { it.optString("id") }
    }

    /**
     * Stamps what changed from [before] to [after], a change made on this device, at [now]: a removed entry is marked gone, an entry that
     * changed without a newer time of its own gets one, and every changed key of the library, the links, our games and the jokes is
     * stamped.
     */
    fun stampChanges(before: JSONObject, after: JSONObject, now: Long) {
        val oldLog = before.optJSONObject("log") ?: JSONObject()
        val newLog = after.optJSONObject("log") ?: JSONObject()
        val gone = after.optJSONObject(GONE) ?: before.optJSONObject(GONE)?.let { JSONObject(it.toString()) } ?: JSONObject()
        for (day in oldLog.keys().asSequence().toList()) {
            val od = oldLog.optJSONObject(day) ?: continue
            val nd = newLog.optJSONObject(day)
            for (id in od.keys().asSequence().toList()) if (nd?.optJSONObject(id) == null) gone.put("$day|$id", now)
        }
        for (day in newLog.keys().asSequence().toList()) {
            val nd = newLog.optJSONObject(day) ?: continue
            for (id in nd.keys().asSequence().toList()) {
                val e = nd.optJSONObject(id) ?: continue
                val was = oldLog.optJSONObject(day)?.optJSONObject(id)
                if (was != null && !same(was, e) && t(e) <= t(was)) e.put("t", maxOf(now, t(was) + 1))
                if (was == null && gone.has("$day|$id")) {
                    // Played again after it was taken back: back for good, newer than the mark.
                    if (t(e) <= gone.optLong("$day|$id")) e.put("t", maxOf(now, gone.optLong("$day|$id") + 1))
                    gone.remove("$day|$id")
                }
            }
        }
        if (gone.length() > 0) after.put(GONE, gone)
        val stamps = after.optJSONObject(STAMPS) ?: before.optJSONObject(STAMPS)?.let { JSONObject(it.toString()) } ?: JSONObject()
        for (k in KEYED) {
            val o = before.optJSONObject(k) ?: JSONObject()
            val n = after.optJSONObject(k) ?: JSONObject()
            for (key in (o.keys().asSequence() + n.keys().asSequence()).toSet()) if (!same(o.opt(key), n.opt(key))) stamps.put("$k|$key", now)
        }
        val oc = customById(before); val nc = customById(after)
        for (id in oc.keys + nc.keys) if (!same(oc[id], nc[id])) stamps.put("custom|$id", now)
        if (!same(before.optJSONArray("jokes"), after.optJSONArray("jokes"))) stamps.put("jokes", now)
        if (stamps.length() > 0) after.put(STAMPS, stamps)
    }

    /** Marks older than the kept log go with it. */
    fun prune(state: JSONObject, cutoff: String) {
        val gone = state.optJSONObject(GONE) ?: return
        for (k in gone.keys().asSequence().toList()) if (k.substringBefore('|') < cutoff) gone.remove(k)
    }

    /**
     * The part of [state] another device needs: the log and marks of [days] (null: all of them), and the rest whole. [since]: only
     * what changed after it (a message through the relay is small); [raw] false leaves out the text a result was read from.
     */
    fun share(state: JSONObject, days: Collection<String>?, since: Long = 0L, raw: Boolean = true, rest: Boolean = true): JSONObject {
        val out = JSONObject().put("v", 1)
        val log = state.optJSONObject("log") ?: JSONObject()
        val outLog = JSONObject()
        for (day in days ?: log.keys().asSequence().toList()) {
            val d = log.optJSONObject(day) ?: continue
            val od = JSONObject()
            for (id in d.keys().asSequence().toList()) {
                val e = d.optJSONObject(id) ?: continue
                if (t(e) <= since && since > 0) continue
                val copy = JSONObject(e.toString())
                if (!raw) copy.optJSONObject("r")?.remove("raw")
                od.put(id, copy)
            }
            if (od.length() > 0) outLog.put(day, od)
        }
        if (outLog.length() > 0) out.put("log", outLog)
        val gone = state.optJSONObject(GONE)
        if (gone != null) {
            val og = JSONObject()
            for (k in gone.keys().asSequence().toList()) {
                val at = gone.optLong(k)
                if ((days == null || k.substringBefore('|') in days) && (since == 0L || at > since)) og.put(k, at)
            }
            if (og.length() > 0) out.put(GONE, og)
        }
        // The games played alone on those evenings (1.11), side by side.
        state.optJSONObject("solo")?.let { solo ->
            val os = JSONObject()
            for (day in days ?: solo.keys().asSequence().toList()) {
                val d = solo.optJSONObject(day) ?: continue
                val od = JSONObject()
                for (game in d.keys().asSequence().toList()) {
                    val g = d.optJSONObject(game) ?: continue
                    val og = JSONObject()
                    for (who in g.keys().asSequence().toList()) g.optJSONObject(who)?.let { side -> if (since == 0L || side.optLong("t") > since) og.put(who, JSONObject(side.toString())) }
                    if (og.length() > 0) od.put(game, og)
                }
                if (od.length() > 0) os.put(day, od)
            }
            if (os.length() > 0) out.put("solo", os)
        }
        if (!rest) return out
        val stamps = state.optJSONObject(STAMPS) ?: JSONObject()
        val os = JSONObject()
        for (k in stamps.keys().asSequence().toList()) if (since == 0L || stamps.optLong(k) > since) os.put(k, stamps.optLong(k))
        if (os.length() > 0) out.put(STAMPS, os)
        fun keyed(name: String) {
            val o = state.optJSONObject(name) ?: return
            val pick = JSONObject()
            for (k in o.keys().asSequence().toList()) if (since == 0L || os.has("$name|$k")) pick.put(k, o.get(k))
            if (pick.length() > 0 || since == 0L) out.put(name, pick)
        }
        KEYED.forEach(::keyed)
        val custom = customById(state).filter { since == 0L || os.has("custom|${it.key}") }.values
        if (custom.isNotEmpty() || since == 0L) out.put("custom", JSONArray().apply { custom.forEach { put(JSONObject(it.toString())) } })
        if (since == 0L || os.has("jokes")) state.optJSONArray("jokes")?.let { out.put("jokes", JSONArray(it.toString())) }
        val people = Couple.all(state).filter { since == 0L || it.at > since }
        if (people.isNotEmpty()) out.put("couple", JSONArray().apply { people.forEach { p -> put(p.toJson().put("who", p.who)) } })
        return out
    }

    /** The days [state] has anything on that is shared: its log, its marks of what was taken back, its games played alone. Oldest first. */
    fun days(state: JSONObject): List<String> {
        val out = sortedSetOf<String>()
        state.optJSONObject("log")?.keys()?.forEach { if (DAY.matches(it)) out += it }
        state.optJSONObject(GONE)?.keys()?.forEach { k -> k.substringBefore('|').takeIf { DAY.matches(it) }?.let { out += it } }
        state.optJSONObject("solo")?.keys()?.forEach { if (DAY.matches(it)) out += it }
        return out.toList()
    }

    /**
     * [state] for another device, in messages each small enough for a line of the link ([limit] bytes): one for each of [days] with
     * anything on it (a very full evening in more than one), then one with the rest (the library, our links and games, the jokes, the
     * looks).
     */
    fun chunks(state: JSONObject, days: Collection<String>, limit: Int = CHUNK): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (day in days) {
            val whole = share(state, listOf(day), rest = false)
            if (whole.length() <= 1) continue
            if (whole.toString().length <= limit) { out += whole; continue }
            // A very full evening: its entries a few at a time; the marks and the games played alone with the first of them.
            val entries = whole.optJSONObject("log")?.optJSONObject(day) ?: JSONObject()
            var cur = JSONObject(whole.toString()).apply { remove("log") }
            var size = cur.toString().length
            for (id in entries.keys().asSequence().toList()) {
                val e = entries.optJSONObject(id) ?: continue
                val add = e.toString().length + id.length + 8
                if (size + add > limit && cur.has("log")) { out += cur; cur = JSONObject().put("v", 1); size = 10 }
                val l = cur.optJSONObject("log") ?: JSONObject().also { cur.put("log", it) }
                (l.optJSONObject(day) ?: JSONObject().also { l.put(day, it) }).put(id, e)
                size += add
            }
            if (cur.length() > 1) out += cur
        }
        out += share(state, emptyList(), rest = true)
        return out
    }

    /** A line of the link holds 65,536 characters; a message of this size leaves room for its envelope. */
    const val CHUNK = 40_000

    /** Which of two choices about a game stands when neither was ever stamped (from before 1.12): on the shelf wins, then "later". */
    private fun prefRank(v: Any?): Int = when (v?.toString()) { "yes" -> 3; "later" -> 2; "no" -> 1; else -> 0 }

    /** [incoming] (another device's [share]) into [state]: the newer of each thing wins. True when anything here changed. */
    fun merge(state: JSONObject, incoming: JSONObject?, now: Long = System.currentTimeMillis()): Boolean {
        if (incoming == null || incoming.optInt("v") != 1) return false
        var changed = false
        val log = obj(state, "log")
        val gone = state.optJSONObject(GONE) ?: JSONObject()
        // Their entries: newer than ours and than our mark of having taken it back.
        incoming.optJSONObject("log")?.let { inLog ->
            for (day in inLog.keys().asSequence().toList()) {
                if (!DAY.matches(day)) continue
                val inDay = inLog.optJSONObject(day) ?: continue
                for (id in inDay.keys().asSequence().toList()) {
                    val theirs = inDay.optJSONObject(id) ?: continue
                    if (!ID.matches(id) || t(theirs) > now + AHEAD_MS) continue
                    val d = log.optJSONObject(day)
                    val mine = d?.optJSONObject(id)
                    if (mine == null) {
                        if (gone.optLong("$day|$id", 0L) >= t(theirs)) continue
                        obj(log, day).put(id, theirs); gone.remove("$day|$id"); changed = true
                    } else {
                        // Their text of a result we have the same of is not news (a relay message leaves it out).
                        val picked = Backup.pick(mine, theirs)
                        if (!same(picked, mine) && !(t(picked) == t(mine) && picked.optJSONObject("r")?.has("raw") == false && mine.optJSONObject("r") != null)) {
                            d.put(id, picked); changed = true
                        }
                    }
                }
            }
        }
        // Their marks: an entry they took back after ours was last changed goes here too.
        incoming.optJSONObject(GONE)?.let { inGone ->
            for (k in inGone.keys().asSequence().toList()) {
                val at = inGone.optLong(k)
                val day = k.substringBefore('|'); val id = k.substringAfter('|')
                if (!DAY.matches(day) || !ID.matches(id) || at > now + AHEAD_MS) continue
                val mine = log.optJSONObject(day)?.optJSONObject(id)
                if (mine != null && t(mine) < at) { log.optJSONObject(day)?.remove(id); changed = true }
                if (mine == null || t(mine) < at) { if (gone.optLong(k, 0L) < at) { gone.put(k, at); changed = true } }
            }
        }
        if (gone.length() > 0) state.put(GONE, gone)
        // The library, our links, our games, the jokes: key by key, the newer stamp wins.
        val inStamps = incoming.optJSONObject(STAMPS) ?: JSONObject()
        val stamps = state.optJSONObject(STAMPS) ?: JSONObject()
        fun newer(key: String): Long? { val ts = inStamps.optLong(key, 0L); return ts.takeIf { it > stamps.optLong(key, 0L) && it <= now + AHEAD_MS } }
        for (name in KEYED) {
            val inO = incoming.optJSONObject(name) ?: JSONObject()
            val keys = inO.keys().asSequence().toSet() + inStamps.keys().asSequence().filter { it.startsWith("$name|") }.map { it.substringAfter('|') }
            for (k in keys) {
                val ts = newer("$name|$k")
                val mineO = state.optJSONObject(name)
                if (ts != null) {
                    if (inO.has(k)) { if (!same(mineO?.opt(k), inO.get(k))) { obj(state, name).put(k, inO.get(k)); changed = true } }
                    else if (mineO?.has(k) == true) { mineO.remove(k); changed = true }
                    stamps.put("$name|$k", ts)
                } else if (inStamps.optLong("$name|$k", 0L) == 0L && stamps.optLong("$name|$k", 0L) == 0L && inO.has(k)) {
                    // From before the stamps (1.12), when the devices never told each other: what one side has and the other does not
                    // is added, never taken away; where both chose differently, the same one stands on both (a game on one's shelf
                    // stays on the shelf; of two links, the later in the alphabet), so the copies end up alike.
                    val mineV = mineO?.opt(k)
                    val wins = when {
                        mineV == null -> true
                        same(mineV, inO.get(k)) -> false
                        name == "prefs" -> prefRank(inO.get(k)) > prefRank(mineV)
                        else -> inO.get(k).toString() > mineV.toString()
                    }
                    if (wins) { obj(state, name).put(k, inO.get(k)); changed = true }
                }
            }
        }
        val inCustom = incoming.optJSONArray("custom")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associateBy { it.optString("id") } } ?: emptyMap()
        val ids = inCustom.keys + inStamps.keys().asSequence().filter { it.startsWith("custom|") }.map { it.substringAfter('|') }
        if (ids.isNotEmpty()) {
            val mine = customById(state).toMutableMap()
            var customChanged = false
            for (id in ids) {
                if (id.isBlank()) continue
                val ts = newer("custom|$id")
                val theirs = inCustom[id]
                if (ts != null) {
                    if (theirs != null && !same(mine[id], theirs)) { mine[id] = theirs; customChanged = true }
                    if (theirs == null && mine.remove(id) != null) customChanged = true
                    stamps.put("custom|$id", ts)
                } else if (theirs != null && inStamps.optLong("custom|$id", 0L) == 0L && stamps.optLong("custom|$id", 0L) == 0L) {
                    val m = mine[id]
                    if (m == null || (!same(m, theirs) && theirs.toString() > m.toString())) { mine[id] = theirs; customChanged = true }
                }
            }
            if (customChanged) { state.put("custom", JSONArray().apply { mine.values.forEach { put(it) } }); changed = true }
        }
        val jokesNewer = newer("jokes")
        if (jokesNewer != null) {
            incoming.optJSONArray("jokes")?.let { j -> if (!same(state.optJSONArray("jokes"), j)) { state.put("jokes", JSONArray(j.toString())); changed = true } }
            stamps.put("jokes", jokesNewer)
        } else if (inStamps.optLong("jokes", 0L) == 0L && stamps.optLong("jokes", 0L) == 0L) {
            // Never stamped on either side: the jokes of both, ours first.
            incoming.optJSONArray("jokes")?.let { j ->
                val mine = state.optJSONArray("jokes") ?: JSONArray()
                val have = (0 until mine.length()).map { mine.optString(it) }.toMutableList()
                var added = false
                for (i in 0 until j.length()) { val x = j.optString(i); if (x.isNotBlank() && x !in have && have.size < 60) { have += x; added = true } }
                if (added) { state.put("jokes", JSONArray(have)); changed = true }
            }
        }
        if (stamps.length() > 0) state.put(STAMPS, stamps)
        // How each of us looks: the newer, person by person (as the TV and the phones have done since 1.9.9).
        incoming.optJSONArray("couple")?.let { a ->
            for (i in 0 until minOf(a.length(), 50)) {
                val o = a.optJSONObject(i) ?: continue
                val p = Person.fromJson(o.optString("who"), o) ?: continue
                if (Couple.offer(state, p)) changed = true
            }
        }
        if (Solo.merge(state, incoming.optJSONObject("solo"), now)) changed = true
        return changed
    }

    private val DAY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val ID = Regex("[A-Za-z0-9_-]{1,60}")
    /** Anything stamped more than a day ahead of this device's clock is not taken (it would win against every later change). */
    private const val AHEAD_MS = 24 * 60 * 60 * 1000L
}
