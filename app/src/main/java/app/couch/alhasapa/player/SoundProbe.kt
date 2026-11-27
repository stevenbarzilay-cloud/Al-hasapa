package app.couch.alhasapa.player

import android.content.Context
import android.webkit.WebView
import androidx.core.content.edit
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.couch.alhasapa.diag.Diag
import java.util.Locale

/**
 * What [SoundScript] counted in a page, read and put into words for the flight recorder, and what follows from it: a site where gaps
 * were counted with the first setting gets the longest buffer from the next time it opens ([SoundPolicy]).
 */
object SoundProbe {
    data class Reading(
        val mode: String,
        /** The page's audio contexts, and how many of them are running. */
        val contexts: Int, val running: Int,
        val sampleRate: Int,
        /** The piece of sound made at a time, and how long after that it is heard; -1 when the browser does not say. */
        val bufferMs: Int, val outputMs: Int,
        /** Whether this browser counts gaps at all (`playbackStats`). */
        val counted: Boolean,
        val seconds: Double,
        val dropouts: Int, val dropoutMs: Int,
        /** How long after it was made the sound was heard, as the browser measured it (average, longest); -1 when it does not say. */
        val latencyMs: Int, val maxLatencyMs: Int,
        val audios: Int, val audiosPlaying: Int, val videos: Int, val videosPlaying: Int,
        /** `<audio>` and `<video>`: times playback stopped to wait for data, times the download stalled. */
        val waits: Int, val stalls: Int,
        val droppedFrames: Int, val frames: Int,
    ) {
        val hasSound: Boolean get() = contexts > 0 || audios > 0 || videos > 0
    }

    /** "\"playback,1,1,48000,21,180,1,953,0,0,175,190,0,0,0,0,0,0,0,0\"" (as evaluateJavascript returns it), or null. */
    fun parse(raw: String?): Reading? {
        val parts = raw?.trim('"')?.split(',') ?: return null
        if (parts.size != 20) return null
        val mode = parts[0].takeIf { SoundScript.Mode.of(it) != null } ?: return null
        val n = parts.drop(1).map { it.trim().toIntOrNull() ?: return null }
        return Reading(
            mode = mode, contexts = n[0], running = n[1], sampleRate = n[2], bufferMs = n[3], outputMs = n[4], counted = n[5] == 1,
            seconds = n[6] / 10.0, dropouts = n[7], dropoutMs = n[8], latencyMs = n[9], maxLatencyMs = n[10],
            audios = n[11], audiosPlaying = n[12], videos = n[13], videosPlaying = n[14], waits = n[15], stalls = n[16],
            droppedFrames = n[17], frames = n[18],
        )
    }

    /** One line for the report, or null when the page has no sound of any kind. */
    fun line(r: Reading, host: String): String? {
        if (!r.hasSound) return null
        val parts = ArrayList<String>()
        if (r.contexts > 0) {
            val asked = when (r.mode) {
                SoundScript.Mode.PLAYBACK.id -> "given the browser's music buffer"
                SoundScript.Mode.LONG.id -> "given the longest buffer"
                else -> "as the page asked"
            }
            val shape = buildString {
                append("the page makes its own sound (${r.sampleRate} Hz, $asked")
                if (r.bufferMs >= 0) append(": ${r.bufferMs} ms a piece")
                // What the browser measured while it played, or else what it expects.
                val heard = if (r.latencyMs > 0) r.latencyMs else r.outputMs
                if (heard > 0) append(", heard $heard ms later")
                append(")")
            }
            parts += shape
            val ran = String.format(Locale.US, "%.0f", r.seconds)
            parts += when {
                r.running == 0 && r.seconds < 0.5 -> "not started yet"
                !r.counted -> "$ran s running; this browser does not count gaps"
                r.dropouts <= 0 -> "$ran s running, no gaps"
                else -> "$ran s running, ${r.dropouts} ${if (r.dropouts == 1) "gap" else "gaps"} (${String.format(Locale.US, "%.2f", r.dropoutMs / 1000.0)} s of silence)"
            }
        }
        if (r.audios > 0) parts += "${r.audios} <audio> (${r.audiosPlaying} playing)"
        if (r.videos > 0) {
            parts += "${r.videos} <video> (${r.videosPlaying} playing)" +
                (if (r.frames > 0) ", ${r.droppedFrames} of ${r.frames} frames dropped" else "")
        }
        if (r.audios + r.videos > 0 && (r.waits > 0 || r.stalls > 0)) parts += "playback waited for data ${r.waits} times, the download stalled ${r.stalls} times"
        return "sound on $host: " + parts.joinToString("; ")
    }

    /**
     * Follows one page while it is open: which readings are worth a line in the report (the first with sound, then when gaps or waits
     * are added, at most one a minute, and the last one when the page closes), and when the site should get the longest buffer.
     */
    class Watch(private val host: String) {
        /** What a reading leads to: lines for the report, and whether the site should get the longest buffer from now on (said once). */
        class Step(val lines: List<String>, val useLong: Boolean)

        private var last: Reading? = null
        private var logged: Reading? = null
        private var loggedAt = Long.MIN_VALUE / 2
        private var escalated = false

        /** A reading taken at [now] (ms). */
        fun onReading(r: Reading, now: Long): Step {
            val out = ArrayList<String>()
            // A new page in the same game starts its count again: the reading before it is the last word on the old page.
            val before = last
            if (before != null && r.seconds + 1.0 < before.seconds) closing()?.let { out += it }
            last = r
            if (!r.hasSound) return Step(out, false)
            val l = logged
            val worse = l != null && (r.dropouts > l.dropouts || r.waits > l.waits || r.stalls > l.stalls)
            if (l == null || r.seconds + 1.0 < l.seconds || (worse && now - loggedAt >= 60_000)) {
                line(r, host)?.let { out += it; logged = r; loggedAt = now }
            }
            var useLong = false
            if (!escalated && r.mode == SoundScript.Mode.PLAYBACK.id && r.counted && r.dropouts >= ESCALATE_AT) {
                escalated = true; useLong = true
                out += "sound on $host: ${r.dropouts} gaps with the browser's music buffer; from the next time this game opens it gets the longest one"
            }
            return Step(out, useLong)
        }

        /** The page is closing: the last reading, if it says more than what was already written. */
        fun closing(): String? {
            val r = last ?: return null
            val l = logged
            if (!r.hasSound || (l != null && r.seconds - l.seconds < 5.0 && r.dropouts == l.dropouts && r.waits == l.waits)) return null
            logged = r
            return line(r, host)
        }

        companion object {
            /** A gap or two when a page starts its sound is common everywhere; this many is a game that cannot be heard properly. */
            const val ESCALATE_AT = 4
        }
    }
}

/**
 * Which buffer a site's sound gets on the TV: the browser's music buffer, or, once gaps were counted with it on this site, the longest
 * one. Kept per site on the device.
 */
object SoundPolicy {
    private const val PREFS = "sound"

    fun key(host: String?): String = host.orEmpty().lowercase().removePrefix("www.")

    fun mode(context: Context, host: String?): SoundScript.Mode =
        SoundScript.Mode.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(host), null)) ?: SoundScript.Mode.PLAYBACK

    fun useLong(context: Context, host: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(key(host), SoundScript.Mode.LONG.id) }
    }
}

/**
 * Puts the TV's two measuring scripts into a game's WebView, to run before each page's own scripts: [SoundScript] in every frame (a
 * game can live in one), [PageBusy] with it.
 */
object SoundInjector {
    /** True when the scripts are registered for document start; false when this WebView cannot do that (the caller then runs them as each page begins). */
    fun install(view: WebView, mode: SoundScript.Mode, withResultWatch: Boolean = false): Boolean = runCatching {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, SoundScript.build(mode), setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(view, PageBusy.INSTALL, setOf("*"))
            // The end of a game (its own share button on the screen), for "save the result": see ResultWatch.
            if (withResultWatch) WebViewCompat.addDocumentStartJavaScript(view, app.couch.alhasapa.results.ResultWatch.INSTALL, setOf("*"))
            true
        } else false
    }.getOrElse { Diag.log("sound: document-start script refused: ${it.javaClass.simpleName}"); false }
}
