package app.couch.alhasapa.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PageProbeTest {
    @Test
    fun thePageIsDescribedInNumbers() {
        assertEquals("2 videos (1 playing), 3 canvases, no frames", PageProbe.describe("\"2,1,3,0\""))
        assertEquals("no videos, 1 canvas, 1 frame", PageProbe.describe("\"0,0,1,1\""))
        assertNull(PageProbe.describe("\"\""))
        assertNull(PageProbe.describe("null"))
        assertNull(PageProbe.describe(null))
    }

    @Test
    fun itCountsAndReadsNothingElse() {
        for (word in listOf("innerText", "textContent", "innerHTML", ".value", "localStorage", "sessionStorage", "cookie", "input", "form")) {
            assertFalse("the probe must not read $word", PageProbe.JS.contains(word))
        }
    }
}
