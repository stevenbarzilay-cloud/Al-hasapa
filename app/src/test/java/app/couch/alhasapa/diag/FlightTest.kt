package app.couch.alhasapa.diag

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class FlightTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun file(name: String) = File(context.filesDir, name)

    @Test
    fun aRunThatEndedWhileInUseIsReportedOnTheNextStartWithWhatHappenedNewestFirst() {
        CrashReport.clear(context)
        Flight.drainForTests()
        file("session.txt").writeText("front 21:40:00.000 PlayerActivity")
        file("flight.txt").writeText("21:39:58.000 player: page loaded (www.nytimes.com)\n21:39:59.000 MAIN THREAD STUCK for 2600 ms, at:\n    at somewhere")
        Flight.start(context)
        val p = Flight.previous!!
        assertTrue(p.endedInFront); assertTrue(p.lastSeen.contains("PlayerActivity"))
        val report = Diagnostics.unexpectedEnd(context)!!
        assertTrue(report.contains("stopped while in use")); assertTrue(report.contains("PlayerActivity"))
        assertTrue("newest first", report.indexOf("MAIN THREAD STUCK") < report.indexOf("page loaded"))
        assertTrue("the previous run's log is kept for the information panel", file("flight-previous.txt").readText().contains("STUCK"))
        Flight.acknowledgePrevious()
        assertNull("seen once, not again", Diagnostics.unexpectedEnd(context))
    }

    @Test
    fun aRunThatWentToTheBackgroundFirstIsANormalEnd() {
        Flight.drainForTests()
        file("session.txt").writeText("back 21:40:00.000 MainActivity")
        file("flight.txt").writeText("21:39:58.000 in the background")
        Flight.start(context)
        assertFalse(Flight.previous!!.endedInFront)
        assertNull(Diagnostics.unexpectedEnd(context))
    }

    @Test
    fun aJavaCrashHasItsOwnReportAndIsNotReportedTwice() {
        Flight.drainForTests()
        file("session.txt").writeText("front 21:40:00.000 MainActivity")
        file("last-crash.txt").writeText("crashed: IllegalStateException")
        Flight.start(context)
        assertNull(Diagnostics.unexpectedEnd(context))
        CrashReport.clear(context)
    }

    @Test
    fun theStartItselfCountsAsNotInFrontUntilAScreenShows() {
        Flight.start(context)
        assertTrue(file("session.txt").readText().startsWith("back"))
        Flight.session(true, "MainActivity")
        assertTrue(file("session.txt").readText().startsWith("front"))
    }

    @Test
    fun theLogKeepsTheLatestLinesAndReachesTheFile() {
        Flight.start(context)
        repeat(400) { Flight.log("line $it") }
        val tail = Flight.tail(1000)
        assertTrue("only the latest few hundred are kept: ${tail.lines().size}", tail.lines().size <= 300)
        assertFalse("the oldest went", tail.lines().any { it.endsWith(" line 50") })
        assertTrue("the newest is last", tail.endsWith("line 399"))
        Flight.flushNow()
        assertTrue("and it is in the file", file("flight.txt").readText().contains("line 399"))
    }

    @Test
    fun theInformationTextHasTheVersionsMemoryAndBothRuns() {
        Flight.drainForTests()
        file("session.txt").writeText("front 21:40:00.000 PlayerActivity")
        file("flight.txt").writeText("21:39:58.000 something earlier")
        Flight.start(context)
        Flight.log("something now")
        val t = Diagnostics.text(context)
        assertTrue(t, t.contains("app ")); assertTrue(t, t.contains("memory: app heap"))
        assertTrue(t, t.contains("ended while in use")); assertTrue(t, t.contains("something earlier")); assertTrue(t, t.contains("something now"))
        assertFalse("nothing about the person: $t", t.contains("@"))
    }

    @Test
    fun theWatchdogWritesDownWhereTheMainThreadIsStuckAndWhenItIsFreeAgain() {
        var now = 0L
        var stalled: Pair<Long, Array<StackTraceElement>>? = null
        var recovered = -1L
        val w = Watchdog(Handler(Looper.getMainLooper()), clock = { now }, stallMs = 2_500,
            onStall = { ms, at -> stalled = ms to at }, onRecover = { recovered = it })
        w.check()
        now = 1_000; w.check(); assertNull("one second of silence is nothing", stalled)
        now = 3_000; w.check()
        assertNotNull("three seconds is a freeze", stalled); assertEquals(3_000L, stalled!!.first); assertTrue(stalled!!.second.isNotEmpty())
        now = 3_500; w.check(); assertEquals("reported once per freeze", -1L, recovered)
        shadowOf(Looper.getMainLooper()).idle()                     // the main thread answers at last
        now = 3_600; w.check()
        assertEquals(3_500L, recovered)
    }

    @Test
    fun aFreezeIsWrittenToTheFileAtOnce() {
        Flight.start(context)
        Watchdog.reportStall(4_000, Thread.currentThread().stackTrace)
        assertTrue(file("flight.txt").readText().contains("MAIN THREAD STUCK for 4000 ms"))
    }
}
