package app.couch.alhasapa.tv

/**
 * How fast a game answers typing on the TV, measured in the page: for each key, the time from its keydown to the frame after the one in
 * which the page handled it (two animation frames: the change is on the screen). Only times are kept, never which key; a summary goes to
 * the flight recorder when the typing pauses. This is the per-game check of 1.9: Krillion and NYT first.
 */
object TypingProbe {
    /** Once per page (it says "ok" when it was already there). At most 400 times are kept between two summaries. */
    const val INSTALL = "(function(){if(window.__couchType)return 'ok';var s=[];" +
        "window.__couchType={take:function(){var a=s;s=[];return a.join(',');}};" +
        "addEventListener('keydown',function(){var t0=performance.now();requestAnimationFrame(function(){requestAnimationFrame(function(){" +
        "if(s.length<400)s.push(Math.round(performance.now()-t0));});});},true);return 'new';})()"

    /** The times since the last summary, and a clean slate. */
    const val TAKE = "(function(){return window.__couchType?window.__couchType.take():'';})()"

    /** "\"40,52,180\"" -> the times; empty for anything else. */
    fun times(raw: String?): List<Long> = raw?.trim('"')?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.filter { it >= 0 }.orEmpty()

    /** "12 keys: half on the screen within 45 ms, 9 in 10 within 120 ms, the slowest 310 ms", or null for none. */
    fun summary(times: List<Long>): String? {
        if (times.isEmpty()) return null
        val s = times.sorted()
        fun at(q: Double) = s[((s.size - 1) * q).toInt()]
        return "${s.size} ${if (s.size == 1) "key" else "keys"}: half on the screen within ${at(0.5)} ms, " +
            "9 in 10 within ${at(0.9)} ms, the slowest ${s.last()} ms"
    }
}
