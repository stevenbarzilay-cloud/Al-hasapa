package app.couch.alhasapa.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How hard a page works the box, as the report says it. The script runs in a real browser in tools/sound_check.cjs. */
class PageBusyTest {
    @Test
    fun aHeavyPageIsSaidOnceWhenItShowsAndAgainWhenTheGameCloses() {
        val w = PageBusy.Watch("dialed.gg")
        assertNull("ten seconds are too few to say anything", w.onSample(PageBusy.parse("\"10000,90,9300,140,1\"")!!))
        assertEquals(
            "page on dialed.gg: its main thread was busy 92% of 30 s so far (270 long tasks, the longest 210 ms): while it is busy it answers no press",
            w.onSample(PageBusy.parse("\"20000,180,18400,210,1\"")!!),
        )
        assertNull("once", w.onSample(PageBusy.parse("\"20000,170,18000,150,1\"")!!))
        assertEquals(
            "page on dialed.gg: its main thread was busy 91% of 50 s in all (440 long tasks, the longest 210 ms): while it is busy it answers no press",
            w.closing(),
        )
    }

    @Test
    fun aPageThatLeavesTheBoxRoomIsNotWorthALine() {
        val light = PageBusy.Watch("nytimes.com")
        repeat(5) { assertNull(light.onSample(PageBusy.parse("\"20000,3,400,180,1\"")!!)) }
        assertNull(light.closing())
        // Busy a fair part of the time, never most of it: one line, at the end.
        val fair = PageBusy.Watch("krillion.io")
        repeat(3) { assertNull(fair.onSample(PageBusy.parse("\"20000,60,6000,120,1\"")!!)) }
        assertTrue(fair.closing()!!.contains("busy 30% of 60 s in all"))
        // A game closed before there was anything to measure.
        assertNull(PageBusy.Watch("x").closing())
    }

    @Test
    fun onlyTheScriptsOwnNumbersAreRead() {
        assertEquals(PageBusy.Sample(20_013, 186, 18_410, 142), PageBusy.parse("\"20013,186,18410,142,1\""))
        for (bad in listOf(null, "", "\"\"", "null", "\"1,2,3\"", "\"20000,x,1,1,1\"", "\"0,0,0,0,1\"", "\"20000,5,900,200,0\"" /* this browser cannot count */))
            assertNull(bad, PageBusy.parse(bad))
        // A sum that is longer than the time looked at (tasks that began before it) never makes more than all of the time.
        val w = PageBusy.Watch("x")
        assertTrue(w.onSample(PageBusy.parse("\"20000,10,26000,9000,1\"")!!)!!.contains("busy 100% of 20 s"))
    }

    @Test
    fun theScriptOnlyCounts() {
        for (banned in listOf("innerText", "textContent", "cookie", "localStorage", "sessionStorage", "fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "eval(",
            "postMessage", "requestAnimationFrame", "setInterval", "document.", ".name", "attribution"))
            assertFalse("the load script must not use $banned", PageBusy.INSTALL.contains(banned))
    }

    /** For the browser check (tools/sound_check.cjs). */
    @Test
    fun dumpScriptsForTheBrowserCheck() {
        val dir = File("build/display-scripts").apply { mkdirs() }
        File(dir, "busy-install.js").writeText(PageBusy.INSTALL)
        File(dir, "busy-take.js").writeText(PageBusy.TAKE)
        assertTrue(File(dir, "busy-install.js").length() > 200)
    }
}
