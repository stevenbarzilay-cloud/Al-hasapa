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
        Flight.drainForTests()                                      // written on the recorder's own thread
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
        Flight.drainForTests()
        assertTrue(file("session.txt").readText().startsWith("back"))
        Flight.session(true, "MainActivity")
        Flight.drainForTests()
        assertTrue("written on the recorder's thread, not the main thread", file("session.txt").readText().startsWith("front"))
        Flight.session(false, "crash", sync = true)
        assertTrue("a crash writes at once", file("session.txt").readText().startsWith("back"))
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
    fun aWatchdogThatWasItselfKeptFromRunningReportsNoFreeze() {
        var now = 0L
        var stalled = false
        val w = Watchdog(Handler(Looper.getMainLooper()), clock = { now }, onStall = { _, _ -> stalled = true })
        w.check()                                               // asks
        shadowOf(Looper.getMainLooper()).idle()                 // the idle main thread answers at once
        now = 9_000; w.check()                                  // the watchdog itself did not run for nine seconds
        assertFalse("the main thread was free all along (the false 2.5 s freeze of the 23 Nov report)", stalled)
        now = 9_300; shadowOf(Looper.getMainLooper()).idle(); now = 9_600; w.check()
        assertFalse(stalled)
    }

    @Test
    fun whileStuckTheWatchdogKeepsSayingForHowLongAndWhere() {
        var now = 0L
        val stuck = ArrayList<Long>()
        var recovered = -1L
        val w = Watchdog(Handler(Looper.getMainLooper()), clock = { now }, onStall = { _, _ -> }, onRecover = { recovered = it },
            onStuck = { ms, at -> stuck += ms; assertTrue(at.isNotEmpty()) })
        w.check()
        now = 3_000; w.check()                                  // the freeze is reported (onStall)
        now = 3_500; w.check(); now = 4_000; w.check()          // and then followed
        assertEquals(listOf(3_500L, 4_000L), stuck)
        shadowOf(Looper.getMainLooper()).idle()                 // answered at 4 000
        now = 4_100; w.check()
        assertEquals(4_000L, recovered)
        now = 4_600; w.check(); assertEquals("nothing more once free", 2, stuck.size)
    }

    @Test
    fun aFreezeWaitingForTheScreenIsLabelledSo() {
        Flight.start(context)
        val at = arrayOf(
            StackTraceElement("android.graphics.HardwareRenderer", "nSyncAndDrawFrame", null, -2),
            StackTraceElement("android.graphics.HardwareRenderer", "syncAndDrawFrame", "HardwareRenderer.java", 433),
            StackTraceElement("android.view.ThreadedRenderer", "draw", "ThreadedRenderer.java", 658),
        )
        assertTrue(Watchdog.drawing(at))
        assertFalse(Watchdog.drawing(Thread.currentThread().stackTrace))
        Watchdog.reportStall(2_998, at)
        Flight.drainForTests()
        val log = file("flight.txt").readText()
        assertTrue(log, log.contains("MAIN THREAD STUCK for 2998 ms (waiting for the screen to take a frame)"))
    }

    @Test
    fun eachRunKnowsWhenThePreviousOneStarted() {
        Flight.drainForTests()
        file("run.txt").delete()
        Flight.start(context)
        assertNull("not known before 1.8.1", Flight.previousStart)
        val after = System.currentTimeMillis()
        Flight.drainForTests()
        Flight.start(context)
        val p = Flight.previousStart!!
        assertTrue("$p", p in (after - 60_000)..after)
    }

    @Test
    fun aFreezeIsWrittenToTheFileAtOnce() {
        Flight.start(context)
        Watchdog.reportStall(4_000, Thread.currentThread().stackTrace)
        Flight.drainForTests()                                      // at once, on the recorder's own thread
        assertTrue(file("flight.txt").readText().contains("MAIN THREAD STUCK for 4000 ms"))
    }

    @Test
    fun aFrozenThreadsStackStaysWholeUnderItsLineWhenTheLogIsShownNewestFirst() {
        Flight.drainForTests()
        file("session.txt").writeText("front 13:36:30.028 PlayerActivity")
        file("flight.txt").writeText("13:36:20.000 player: page loaded\n13:36:33.100 MAIN THREAD STUCK for 2600 ms, at:\n    at a.b.Inner(x:1)\n    at a.b.Outer(x:2)\n13:36:34.000 later")
        Flight.start(context)
        val r = Diagnostics.unexpectedEnd(context)!!
        val stuck = r.indexOf("MAIN THREAD STUCK"); val inner = r.indexOf("a.b.Inner"); val outer = r.indexOf("a.b.Outer")
        assertTrue("the newest entry first", r.indexOf("later") < stuck)
        assertTrue("the stack under its line, innermost first: $r", stuck < inner && inner < outer)
        assertTrue(r.indexOf("page loaded") > outer)
    }
}
