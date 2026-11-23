package app.couch.alhasapa.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TypingProbeTest {
    @Test
    fun theTimesAreSummedUpPlainly() {
        val t = TypingProbe.times("\"40,52,180,33,61,45,48,50,47,300\"")
        assertEquals(10, t.size)
        assertEquals("10 keys: half on the screen within 48 ms, 9 in 10 within 180 ms, the slowest 300 ms", TypingProbe.summary(t))
        assertEquals("1 key: half on the screen within 70 ms, 9 in 10 within 70 ms, the slowest 70 ms", TypingProbe.summary(listOf(70)))
        assertNull(TypingProbe.summary(TypingProbe.times("\"\"")))
        assertNull(TypingProbe.summary(TypingProbe.times(null)))
    }

    @Test
    fun itMeasuresAndNeverReadsWhichKey() {
        for (word in listOf(".key", ".code", "keyCode", ".which", "value", "innerText", "textContent", "localStorage", "cookie")) {
            assertFalse("the probe must not read $word", TypingProbe.INSTALL.contains(word))
        }
    }
}
