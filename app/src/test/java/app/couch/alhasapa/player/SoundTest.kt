package app.couch.alhasapa.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Sound on the TV: what the script asks the browser for, what its numbers become in the report, and when a site moves on to the longest
 * buffer. The script itself runs in a real browser in tools/sound_check.cjs (the files are written here).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class SoundTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun reading(
        mode: String = "playback", contexts: Int = 1, running: Int = 1, counted: Boolean = true, seconds: Int = 95, dropouts: Int = 0,
        dropoutMs: Int = 0, audios: Int = 0, videos: Int = 0, waits: Int = 0, frames: Int = 0, dropped: Int = 0,
    ): String = "\"$mode,$contexts,$running,48000,21,180,${if (counted) 1 else 0},${seconds * 10},$dropouts,$dropoutMs,175,190," +
        "$audios,${audios.coerceAtMost(1)},$videos,${videos.coerceAtMost(1)},$waits,0,$dropped,$frames\""

    @Test
    fun thePageIsAskedForALongerBufferOnlyWhereItAskedForNone() {
        val playback = SoundScript.build(SoundScript.Mode.PLAYBACK)
        assertTrue(playback.contains("MODE='playback'"))
        assertTrue(SoundScript.build(SoundScript.Mode.LONG).contains("MODE='long'"))
        assertTrue(SoundScript.build(SoundScript.Mode.PAGE).contains("MODE='page'"))
        assertFalse("nothing left to fill in", playback.contains("__MODE__"))
        // Its reach: the audio context's one setting, and counting. Nothing of the page is read, kept or sent.
        for (banned in listOf("innerText", "textContent", "cookie", "localStorage", "sessionStorage", "fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "eval(",
            "postMessage", ".value", ".src", "currentSrc", "getChannelData", "createAnalyser", "MediaRecorder", "captureStream", "createMediaStreamDestination"))
            assertFalse("the sound script must not use $banned", playback.contains(banned))
        assertEquals("the one thing it changes", 1, Regex("p\\.latencyHint=").findAll(playback).count())
    }

    @Test
    fun theNumbersBecomeOneLineOfTheReport() {
        val r = SoundProbe.parse(reading())!!
        assertEquals("playback", r.mode); assertEquals(48000, r.sampleRate); assertEquals(95.0, r.seconds, 0.01); assertTrue(r.counted)
        assertEquals(
            "sound on dialed.gg: the page makes its own sound (48000 Hz, given the browser's music buffer: 21 ms a piece, heard 175 ms later); " +
                "95 s running, no gaps",
            SoundProbe.line(r, "dialed.gg"),
        )
        val gaps = SoundProbe.line(SoundProbe.parse(reading(mode = "long", dropouts = 12, dropoutMs = 430))!!, "dialed.gg")!!
        assertTrue(gaps, gaps.contains("given the longest buffer") && gaps.contains("12 gaps (0.43 s of silence)"))
        assertTrue(SoundProbe.line(SoundProbe.parse(reading(dropouts = 1, dropoutMs = 21))!!, "x")!!.contains("1 gap (0.02 s of silence)"))
        assertTrue(SoundProbe.line(SoundProbe.parse(reading(counted = false))!!, "x")!!.contains("this browser does not count gaps"))
        // A page that plays files instead of making the sound itself, and one with a video.
        assertEquals(
            "sound on bandle.app: 2 <audio> (1 playing); playback waited for data 3 times, the download stalled 0 times",
            SoundProbe.line(SoundProbe.parse(reading(contexts = 0, running = 0, audios = 2, waits = 3))!!, "bandle.app"),
        )
        assertTrue(SoundProbe.line(SoundProbe.parse(reading(contexts = 0, running = 0, videos = 1, frames = 3000, dropped = 12))!!, "flickle.app")!!
            .contains("1 <video> (1 playing), 12 of 3000 frames dropped"))
        // A page with no sound of any kind is not worth a line; and anything that is not the script's own line is nothing.
        assertNull(SoundProbe.line(SoundProbe.parse(reading(contexts = 0, running = 0))!!, "wordle"))
        for (bad in listOf(null, "", "\"\"", "null", "\"playback,1,2\"", "\"loud,1,1,48000,21,180,1,950,0,0,175,190,0,0,0,0,0,0,0,0\"",
            "\"playback,1,1,48000,x,180,1,950,0,0,175,190,0,0,0,0,0,0,0,0\""))
            assertNull(bad, SoundProbe.parse(bad))
    }

    @Test
    fun aGameIsWrittenDownOnceWhenItsSoundStartsAgainWhenGapsAppearAndWhenItCloses() {
        val w = SoundProbe.Watch("dialed.gg")
        fun step(raw: String, at: Long) = w.onReading(SoundProbe.parse(raw)!!, at)
        assertTrue("nothing to say about a silent page", step(reading(contexts = 0, running = 0), 10_000).lines.isEmpty())
        assertEquals(1, step(reading(seconds = 10), 30_000).lines.size)
        assertTrue("the same, a little later: not again", step(reading(seconds = 30), 50_000).lines.isEmpty())
        // Gaps appear: said, but not more than once a minute.
        val first = step(reading(seconds = 50, dropouts = 2, dropoutMs = 40), 100_000)
        assertEquals(1, first.lines.size); assertFalse("two gaps are not a reason to change anything", first.useLong)
        assertTrue(step(reading(seconds = 70, dropouts = 3, dropoutMs = 60), 120_000).lines.isEmpty())
        // Four: the site gets the longest buffer from the next time it opens, said once.
        val many = step(reading(seconds = 130, dropouts = 6, dropoutMs = 130), 180_000)
        assertTrue(many.useLong)
        assertTrue(many.lines.joinToString("|"), many.lines.any { it.contains("6 gaps (0.13 s of silence)") } && many.lines.any { it.contains("the longest one") })
        assertFalse("once", step(reading(seconds = 150, dropouts = 9, dropoutMs = 200), 200_000).useLong)
        // Closing: the last word, when it says more than the last line did.
        assertNotNull(w.closing())
        assertNull("and only once", w.closing())
    }

    @Test
    fun withTheLongestBufferAlreadyThereIsNothingFurtherToMoveTo() {
        val w = SoundProbe.Watch("dialed.gg")
        val step = w.onReading(SoundProbe.parse(reading(mode = "long", dropouts = 20, dropoutMs = 900))!!, 10_000)
        assertFalse(step.useLong)
        assertEquals("but it is in the report", 1, step.lines.size)
        // A browser that does not count gaps never moves a site on by itself.
        assertFalse(SoundProbe.Watch("x").onReading(SoundProbe.parse(reading(counted = false, dropouts = -1))!!, 0).useLong)
    }

    @Test
    fun aSiteThatHadGapsKeepsTheLongestBufferAndOnlyThatSite() {
        context.getSharedPreferences("sound", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(SoundScript.Mode.PLAYBACK, SoundPolicy.mode(context, "dialed.gg"))
        SoundPolicy.useLong(context, "www.dialed.gg")
        assertEquals(SoundScript.Mode.LONG, SoundPolicy.mode(context, "dialed.gg"))
        assertEquals("with or without www, in any case", SoundScript.Mode.LONG, SoundPolicy.mode(context, "WWW.Dialed.gg"))
        assertEquals(SoundScript.Mode.PLAYBACK, SoundPolicy.mode(context, "bandle.app"))
        assertEquals(SoundScript.Mode.PLAYBACK, SoundPolicy.mode(context, null))
    }

    /** Writes the scripts the browser check (tools/sound_check.cjs) runs in real Chromium. Always rewritten, harmless. */
    @Test
    fun dumpScriptsForTheBrowserCheck() {
        val dir = File("build/display-scripts").apply { mkdirs() }
        for (m in SoundScript.Mode.entries) File(dir, "sound-${m.id}.js").writeText(SoundScript.build(m))
        File(dir, "sound-take.js").writeText(SoundScript.TAKE)
        assertTrue(File(dir, "sound-playback.js").length() > 1_000)
    }
}
