package app.couch.alhasapa.remote

import android.content.Context
import androidx.core.content.edit
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.data.Person
import org.json.JSONArray
import org.json.JSONObject

/**
 * A friend's couch this phone knows (2.0): its id and name, the TVs it was met on, and who the phone's holder is there (a player of
 * that couch, chosen there: never matched by name with a player of the home). [kept]: saved for the next visits; else it is known for
 * one evening only and forgotten with it.
 */
data class SavedCouch(
    val id: String, val name: String, val tvs: List<String> = emptyList(),
    val me: String? = null, val meName: String? = null, val kept: Boolean = true, val at: Long = 0L,
    /** It was this phone's home once, and its state is still kept here (see CouchSetup.setHome). */
    val archived: Boolean = false,
    /** The phone's holder as they added themselves there (a guest, or someone who comes often): said again until that couch's TV names them. */
    val made: Person? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("tvs", JSONArray(tvs)).put("kept", kept).put("at", at).apply {
        if (me != null) put("me", me)
        if (meName != null) put("meName", meName)
        if (archived) put("archived", true)
        if (made != null) put("made", made.toJson().put("who", made.who))
    }

    companion object {
        fun fromJson(o: JSONObject?): SavedCouch? {
            val id = o?.optString("id")?.takeIf { Couches.isId(it) } ?: return null
            val tvs = o.optJSONArray("tvs")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList()
            return SavedCouch(
                id, o.optString("name").take(Couches.MAX_NAME), tvs, o.optString("me").takeIf { Couple.isId(it) },
                o.optString("meName").takeIf { it.isNotEmpty() }?.take(Couple.MAX_NAME), o.optBoolean("kept", true), o.optLong("at", 0L),
                o.optBoolean("archived", false),
                o.optJSONObject("made")?.let { Person.fromJson(it.optString("who"), it) },
            )
        }
    }
}

/**
 * A visit (2.0): the phone's holder is a guest at [couch] ([name]) this [evening], through its TV [tv], as the player [me] of that
 * couch ([meName]). It ends when they say so, or with the evening; it never begins again by itself.
 */
data class Visit(val couch: String, val name: String, val tv: String, val me: String, val meName: String, val evening: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("couch", couch).put("name", name).put("tv", tv).put("me", me).put("meName", meName).put("evening", evening).put("at", at)

    companion object {
        fun fromJson(o: JSONObject?): Visit? {
            val couch = o?.optString("couch")?.takeIf { Couches.isId(it) } ?: return null
            val me = o.optString("me").takeIf { Couple.isId(it) } ?: return null
            return Visit(couch, o.optString("name"), o.optString("tv"), me, o.optString("meName"), o.optString("evening"), o.optLong("at", 0L))
        }
    }
}

/**
 * Where a game played alone is counted (2.0): the couch ([couch], [name]) and the player there ([who], [whoName]). Chosen once for a
 * game and a puzzle's day, and kept with it: a result typed later, or sent again, goes where it was meant to, whatever the phone is
 * connected to by then.
 */
data class SoloTarget(val couch: String, val name: String, val who: String, val whoName: String, val home: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("couch", couch).put("name", name).put("who", who).put("whoName", whoName).put("home", home)

    companion object {
        fun fromJson(o: JSONObject?): SoloTarget? {
            val who = o?.optString("who")?.takeIf { Couple.isId(it) } ?: return null
            return SoloTarget(o.optString("couch"), o.optString("name"), who, o.optString("whoName"), o.optBoolean("home", false))
        }
    }
}

/**
 * What a phone keeps of the couches it knows (2.0), beside the home couch's own state: which TVs are the home's, the friends' couches
 * saved for the next visits, the visit under way, and where each game played alone goes. In the app's private preferences; nothing here
 * is ever sent to another device.
 */
class CouchStore(context: Context, name: String = "couches") {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    // ---- the home couch ----

    /** The TVs of the home couch (their ids, as [RemoteStore] keeps their tokens). */
    var homeTvs: Set<String>
        get() = prefs.getStringSet("home_tvs", emptySet())!!.toSet()
        set(v) = prefs.edit { putStringSet("home_tvs", v) }

    fun addHomeTv(tv: String) { if (tv.isNotEmpty() && tv !in homeTvs) homeTvs = homeTvs + tv }

    /**
     * A phone from before 2.0 that was paired with several TVs and cannot tell which is its home's: the TVs still to be asked about
     * (once each, as it meets them); its data stays as it is meanwhile, and nothing of it goes to any of them.
     */
    var askHome: Set<String>
        get() = prefs.getStringSet("ask_home", emptySet())!!.toSet()
        set(v) = prefs.edit { if (v.isEmpty()) remove("ask_home") else putStringSet("ask_home", v) }

    /** Whether what the phone knew before 2.0 was sorted into couches (done once, see CouchSetup). */
    var migrated: Boolean
        get() = prefs.getBoolean("migrated", false)
        set(v) = prefs.edit { putBoolean("migrated", v) }

    // ---- friends' couches ----

    fun saved(): List<SavedCouch> {
        val a = runCatching { JSONArray(prefs.getString("saved", "[]")) }.getOrElse { JSONArray() }
        return (0 until a.length()).mapNotNull { SavedCouch.fromJson(a.optJSONObject(it)) }
    }

    fun saved(id: String): SavedCouch? = saved().firstOrNull { it.id == id }

    /** The couch the TV [tv] was last known to be of, among the friends' couches. */
    fun savedByTv(tv: String): SavedCouch? = saved().firstOrNull { tv in it.tvs }

    fun save(c: SavedCouch) = write(saved().filter { it.id != c.id } + c)

    fun remove(id: String) {
        write(saved().filter { it.id != id })
        prefs.edit { remove("people_$id") }
        if (visit?.couch == id) visit = null
    }

    private fun write(list: List<SavedCouch>) {
        prefs.edit { putString("saved", JSONArray().apply { for (c in list.sortedBy { it.name }) put(c.toJson()) }.toString()) }
    }

    /** The players of a friend's couch as its TV last named them (for the choice of who the phone's holder is there, and the contests). */
    fun people(couch: String): List<Person> {
        val o = runCatching { JSONObject(prefs.getString("people_$couch", "{}")!!) }.getOrElse { JSONObject() }
        return o.keys().asSequence().toList().mapNotNull { Person.fromJson(it, o.optJSONObject(it)) }
    }

    fun setPeople(couch: String, people: List<Person>) {
        if (!Couches.isId(couch)) return
        val o = JSONObject()
        for (p in people.take(KeyLink.MAX_OTHERS + 2)) o.put(p.who, p.toJson())
        prefs.edit { putString("people_$couch", o.toString()) }
    }

    // ---- the visit ----

    /** The visit as it was written, whatever the evening (see [visitNow]). */
    var visit: Visit?
        get() = Visit.fromJson(runCatching { JSONObject(prefs.getString("visit", "")!!) }.getOrNull())
        set(v) = prefs.edit { if (v == null) remove("visit") else putString("visit", v.toJson().toString()) }

    /**
     * The visit under way, if its evening is still on: a visit ends with its evening and never begins again by itself (the next day
     * the phone is at home again, whatever network it is on). A couch known for that evening only is forgotten with it.
     */
    fun visitNow(evening: String = Couple.evening()): Visit? {
        val v = visit ?: return null
        if (v.evening == evening) return v
        endVisit()
        return null
    }

    /** The visit is over: the phone is at home again. A couch that was not saved for the next visits is forgotten (its pairing too). */
    fun endVisit(): Visit? {
        val v = visit ?: return null
        visit = null
        val c = saved(v.couch)
        if (c == null || !c.kept) {
            write(saved().filter { it.id != v.couch })
            prefs.edit { remove("people_${v.couch}") }
            if (v.tv.isNotEmpty()) RemoteStore(app).forgetTv(v.tv)
        }
        return v
    }

    // ---- where a game played alone goes ----

    private fun targets(): JSONObject = runCatching { JSONObject(prefs.getString("targets", "{}")!!) }.getOrElse { JSONObject() }

    /** Where [game] of the puzzle's [day], played alone, was said to go; null when it was not said yet. */
    fun target(day: String, game: String): SoloTarget? = SoloTarget.fromJson(targets().optJSONObject("$day|$game"))

    /** Kept with the game and the day; older days are let go of. */
    fun setTarget(day: String, game: String, t: SoloTarget, keepFrom: String = day) {
        val all = targets()
        for (k in all.keys().asSequence().toList()) if (k.substringBefore('|') < keepFrom) all.remove(k)
        all.put("$day|$game", t.toJson())
        prefs.edit { putString("targets", all.toString()) }
    }

    fun clearTargets() { prefs.edit { remove("targets") } }
}
