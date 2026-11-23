package app.couch.alhasapa.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PageProbeTest {
    @Test
    fun thePageIsDescribedInNumbers() {
        assertEquals("2 videos (1 playing), 3 canvases, no frames; 840 elements, 2400 px tall in a 1080 px window",
            PageProbe.describe("\"2,1,3,0,840,2400,1080\""))
        assertEquals("a page that stayed white", "no videos, no canvases, 1 frame; 9 elements, 0 px tall in a 1080 px window",
            PageProbe.describe("\"0,0,0,1,9,0,1080\""))
        assertNull("the old shape", PageProbe.describe("\"0,0,1,1\""))
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
