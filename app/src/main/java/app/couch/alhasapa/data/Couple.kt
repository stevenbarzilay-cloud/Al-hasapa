package app.couch.alhasapa.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One of us as every device shows us (1.9.9): a name and a piece (a shape and a colour; empty for the default), and when they were last
 * changed. The TV and each phone keep their own copy of the state; where two copies meet (a phone connecting to the TV), the newer one
 * wins, person by person.
 */
data class Person(val who: String, val name: String, val shape: String, val color: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("shape", shape).put("color", color).put("at", at)

    companion object {
        /** Null for anything that is not one of us with a name. */
        fun fromJson(who: String, o: JSONObject?): Person? {
            if (o == null || who !in Couple.WHO) return null
            val name = o.optString("name").trim().take(Couple.MAX_NAME)
            if (name.isEmpty()) return null
            return Person(who, name, o.optString("shape").take(24), o.optString("color").take(24), o.optLong("at", 0L).coerceAtLeast(0L))
        }
    }
}

object Couple {
    val WHO = listOf("a", "b")
    const val MAX_NAME = 20

    /** [who] as [state] has them. */
    fun person(state: JSONObject, who: String): Person {
        val (a, b) = CouchState.names(state)
        val piece = state.optJSONObject("pieces")?.optJSONObject(who)
        return Person(who, if (who == "b") b else a, piece?.optString("shape").orEmpty(), piece?.optString("color").orEmpty(), stamp(state, who))
    }

    /** When [who]'s name or piece last changed, as far as [state] knows (0: never, or before 1.9.9). */
    fun stamp(state: JSONObject, who: String): Long = state.optJSONObject("idAt")?.optLong(who, 0L) ?: 0L

    /** [p] into [state] if it is newer than what the state has. True when anything changed. */
    fun offer(state: JSONObject, p: Person): Boolean {
        if (p.who !in WHO || p.at <= stamp(state, p.who)) return false
        put(state, p)
        return true
    }

    /** [p] into [state] as it is: a change made on this device (stamped by the caller), or a newer one from another. */
    fun put(state: JSONObject, p: Person) {
        val names = state.optJSONObject("names") ?: JSONObject().also { state.put("names", it) }
        names.put(p.who, p.name.trim().take(MAX_NAME))
        val pieces = state.optJSONObject("pieces") ?: JSONObject().also { state.put("pieces", it) }
        val piece = pieces.optJSONObject(p.who) ?: JSONObject().also { pieces.put(p.who, it) }
        if (p.shape.isNotEmpty()) piece.put("shape", p.shape)
        if (p.color.isNotEmpty()) piece.put("color", p.color)
        val at = state.optJSONObject("idAt") ?: JSONObject().also { state.put("idAt", it) }
        at.put(p.who, p.at)
    }

    /**
     * The hub changed a name or a piece (it does not stamp them itself): [after] is stamped [now] for each of us whose name or piece
     * differs from [before].
     */
    fun stampChanges(before: JSONObject, after: JSONObject, now: Long) {
        for (w in WHO) {
            val b = person(before, w)
            val a = person(after, w)
            if (a.name != b.name || a.shape != b.shape || a.color != b.color) {
                val at = after.optJSONObject("idAt") ?: JSONObject().also { after.put("idAt", it) }
                at.put(w, maxOf(now, b.at + 1))
            }
        }
    }
}

/**
 * A note one of us leaves the other (1.9.9): a few words and a small drawing, tied to a game (shown on the TV when that game was played)
 * or to none (shown on the TV's home). Written on a phone, kept on the TV.
 */
data class Note(
    val id: String,
    /** "a" or "b": who wrote it (for the other one). */
    val from: String,
    /** The game it waits for; null: the next time the TV's home shows. */
    val game: String?,
    val text: String,
    /** The drawing: strokes, each its points as x, y pairs in thousandths of the drawing's width and height. */
    val doodle: List<IntArray>,
    val at: Long,
    /** When it was shown on the TV (0: not yet). */
    val seen: Long = 0L,
) {
    // The drawing's arrays compare by what is in them.
    override fun equals(other: Any?): Boolean = other is Note && id == other.id && from == other.from && game == other.game &&
        text == other.text && at == other.at && seen == other.seen && doodle.size == other.doodle.size &&
        doodle.indices.all { doodle[it].contentEquals(other.doodle[it]) }

    override fun hashCode(): Int = id.hashCode()

    fun toJson(): JSONObject = JSONObject().put("id", id).put("from", from).put("text", text).put("at", at)
        .apply { if (game != null) put("game", game); if (seen > 0) put("seen", seen) }
        .put("d", JSONArray().apply { for (s in doodle) put(JSONArray().apply { for (v in s) put(v) }) })

    companion object {
        /** Null for anything that is not a note: no id, nobody of us, nothing in it. Over-long parts are cut to [Notes]' limits. */
        fun fromJson(o: JSONObject?): Note? {
            if (o == null) return null
            val id = o.optString("id").take(40)
            val from = o.optString("from")
            if (id.isEmpty() || from !in Couple.WHO) return null
            val text = o.optString("text").trim().take(Notes.MAX_TEXT)
            val strokes = ArrayList<IntArray>()
            var points = 0
            o.optJSONArray("d")?.let { d ->
                for (i in 0 until minOf(d.length(), Notes.MAX_STROKES)) {
                    val s = d.optJSONArray(i) ?: continue
                    val n = minOf(s.length() / 2, Notes.MAX_POINTS - points)
                    if (n <= 0) break
                    strokes += IntArray(n * 2) { s.optInt(it, 0).coerceIn(0, 1000) }
                    points += n
                }
            }
            if (text.isEmpty() && strokes.isEmpty()) return null
            return Note(id, from, o.optString("game").takeIf { it.isNotBlank() }?.take(40), text, strokes, o.optLong("at", 0L), o.optLong("seen", 0L))
        }
    }
}

object Notes {
    const val MAX_TEXT = 140
    const val MAX_STROKES = 60
    const val MAX_POINTS = 1_500
    /** The TV keeps at most this many notes; the oldest seen ones go first. */
    const val KEEP = 60

    fun all(state: JSONObject): List<Note> {
        val a = state.optJSONArray("notes") ?: return emptyList()
        return (0 until a.length()).mapNotNull { Note.fromJson(a.optJSONObject(it)) }
    }

    /** [n] kept in [state] (once: a note sent twice is kept once). True when it is new. */
    fun add(state: JSONObject, n: Note): Boolean {
        val list = all(state).toMutableList()
        if (list.any { it.id == n.id }) return false
        list += n
        while (list.size > KEEP) {
            val drop = list.filter { it.seen > 0 }.minByOrNull { it.at } ?: list.minBy { it.at }
            list.remove(drop)
        }
        write(state, list)
        return true
    }

    fun markSeen(state: JSONObject, id: String, now: Long) {
        write(state, all(state).map { if (it.id == id && it.seen == 0L) it.copy(seen = now) else it })
    }

    /** The notes waiting for [game] (not shown yet), oldest first. */
    fun waitingFor(state: JSONObject, game: String): List<Note> = all(state).filter { it.seen == 0L && it.game == game }.sortedBy { it.at }

    /**
     * What the TV's home shows now, oldest first: the notes tied to no game, and those whose game was played on [day] after the note was
     * written (a game left without its result saved; a saved result shows them in the game itself). A note for a game played earlier
     * that day waits for the next time it is played.
     */
    fun forHome(state: JSONObject, day: String): List<Note> = all(state)
        .filter { it.seen == 0L && (it.game == null || playedAfter(state, day, it.game, it.at)) }
        .sortedBy { it.at }

    private fun playedAfter(state: JSONObject, day: String, game: String, at: Long): Boolean =
        (CouchState.entry(state, day, game)?.optLong("t", 0L) ?: 0L) > at

    private fun write(state: JSONObject, list: List<Note>) {
        state.put("notes", JSONArray().apply { for (n in list) put(n.toJson()) })
    }
}
