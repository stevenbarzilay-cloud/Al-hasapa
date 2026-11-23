package app.couch.alhasapa.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The touchpad's road to the TV in numbers: what a smooth stroke looks like, and one held back by a dozing Wi-Fi. */
class PadTimingTest {
    @Test
    fun aSmoothStrokeArrivesAsItWasSent() {
        val t = PadTiming()
        // The phone's clock and the TV's have nothing to do with each other: 5,000,000 apart here, and 3 ms on the way.
        for (i in 0 until 60) { t.arrived(at = 5_000_000L + 16L * i + 3, sent = 16L * i + 1); t.waited(2) }
        assertEquals(
            "touchpad: 60 moves in 0.9 s; apart: half within 16 ms, 9 in 10 within 16 ms, the longest 16 ms; " +
                "held up on the way: half under 0 ms, 9 in 10 under 0 ms, the worst 0 ms; then waited for the TV: half under 2 ms, the longest 2 ms",
            t.summary(),
        )
        assertNull("a clean slate after it was said", t.summary())
    }

    @Test
    fun movesHeldBackAndHandedOverInBunchesShowAsLate() {
        val t = PadTiming()
        // Sent every 16 ms; the TV's radio wakes every 100 ms and gets everything sent since at once.
        for (i in 0 until 50) {
            val sent = 1_000L + 16L * i
            val wake = ((sent + 99) / 100) * 100            // the next time the radio listens
            t.arrived(at = 700_000L + wake + 2, sent = sent)
        }
        val s = t.summary()!!
        assertTrue(s, s.contains("touchpad: 50 moves"))
        assertTrue("most arrive on each other's heels, then a long wait: $s", s.contains("half within 0 ms") && s.contains("the longest 100 ms"))
        assertTrue("held up by most of a tenth of a second: $s", Regex("the worst (8\\d|9\\d) ms").containsMatchIn(s))
        assertTrue("and how many of them were felt: $s", Regex("\\((\\d+) of 50 by 50 ms or more\\)").find(s)!!.groupValues[1].toInt() in 15..35)
    }

    @Test
    fun aPhoneThatDoesNotSayWhenItSentIsStillMeasuredByArrival() {
        val t = PadTiming()
        for (i in 0 until 10) t.arrived(at = 100L * i, sent = 0)
        val s = t.summary()!!
        assertTrue(s, s.contains("apart: half within 100 ms") && s.contains("the phone's app does not say when it sent them"))
    }

    @Test
    fun aNudgeIsNotAStroke() {
        val t = PadTiming()
        for (i in 0 until 3) t.arrived(at = 16L * i, sent = 16L * i)
        assertNull(t.summary())
        // And what was counted is gone: the next stroke starts from nothing.
        for (i in 0 until 8) t.arrived(at = 10_000L + 16L * i, sent = 16L * i)
        assertTrue(t.summary()!!.contains("touchpad: 8 moves in 0.1 s"))
    }
}
