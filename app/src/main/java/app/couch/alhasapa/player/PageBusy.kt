package app.couch.alhasapa.player

import java.util.Locale

/**
 * How hard a game's page works the TV box, for the report: the share of the time its main thread spent in long tasks (pieces of work
 * of 50 ms or more, during which the page answers nothing: not a press of the paw, not a key). Dialed's first screen "answered the
 * mouse slowly" on the box; in a desktop browser slowed to a sixth, that page's main thread is never idle (its moving picture alone
 * takes all of it). This number says so from the box itself, for any game, without guessing.
 *
 * The browser does the counting (a `PerformanceObserver` for "longtask"); the script only adds up durations. It asks for no frames of
 * its own and reads nothing of the page.
 */
object PageBusy {
    /** Runs at document start. `take()` gives the numbers since the last take: the time looked at, the long tasks, their sum, the longest. */
    const val INSTALL = "(function(){if(window.__couchBusy)return;var B={n:0,ms:0,max:0,t0:performance.now()};" +
        "try{Object.defineProperty(window,'__couchBusy',{value:B})}catch(e){window.__couchBusy=B}" +
        "try{new PerformanceObserver(function(l){var e=l.getEntries();for(var i=0;i<e.length;i++){var d=e[i].duration;B.n++;B.ms+=d;if(d>B.max)B.max=d}})" +
        ".observe({type:'longtask',buffered:true})}catch(e){B.none=1}" +
        "B.take=function(){var now=performance.now(),r=[Math.round(now-B.t0),B.n,Math.round(B.ms),Math.round(B.max),B.none?0:1].join(',');" +
        "B.n=0;B.ms=0;B.max=0;B.t0=now;return r}})()"

    const val TAKE = "(function(){try{return window.__couchBusy?window.__couchBusy.take():''}catch(e){return ''}})()"

    data class Sample(val windowMs: Long, val tasks: Int, val busyMs: Long, val longestMs: Long)

    /** "\"20013,186,18410,142,1\"" (as evaluateJavascript returns it); null for anything else, and for a browser that cannot count. */
    fun parse(raw: String?): Sample? {
        val n = raw?.trim('"')?.split(',')?.map { it.trim().toLongOrNull() ?: return null }?.takeIf { it.size == 5 } ?: return null
        if (n[4] != 1L || n[0] <= 0 || n[1] < 0 || n[2] < 0) return null
        return Sample(n[0], n[1].toInt(), n[2], n[3])
    }

    /** Follows one game while it is open. A page that leaves the box room to breathe is not worth a line. */
    class Watch(private val host: String) {
        private var windowMs = 0L
        private var busyMs = 0L
        private var tasks = 0
        private var longest = 0L
        private var said = false

        /** A sample came in. A line for the report the first time the page turns out to be heavy, else null. */
        fun onSample(s: Sample): String? {
            windowMs += s.windowMs; busyMs += s.busyMs.coerceAtMost(s.windowMs); tasks += s.tasks; longest = maxOf(longest, s.longestMs)
            if (said || windowMs < MIN_WINDOW_MS || share() < HEAVY) return null
            said = true
            return line("so far")
        }

        /** The game is closing: the whole of it, if it was heavy at any point or busy a fair part of the time. */
        fun closing(): String? = if (windowMs >= MIN_WINDOW_MS && (said || share() >= NOTABLE)) line("in all") else null

        private fun share(): Double = if (windowMs <= 0) 0.0 else busyMs.toDouble() / windowMs

        private fun line(scope: String): String =
            "page on $host: its main thread was busy ${(share() * 100).toInt()}% of ${String.format(Locale.US, "%.0f", windowMs / 1000.0)} s $scope " +
                "($tasks long tasks, the longest $longest ms): while it is busy it answers no press"

        companion object {
            const val MIN_WINDOW_MS = 15_000L
            const val HEAVY = 0.5
            const val NOTABLE = 0.2
        }
    }
}
