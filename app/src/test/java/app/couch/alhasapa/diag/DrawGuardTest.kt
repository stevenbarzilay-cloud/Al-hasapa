package app.couch.alhasapa.diag

import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.diag.DrawGuard.Act
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** A game that holds the TV's drawing stuck is closed, its site goes light, and the app ends only if it stays stuck after that. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class DrawGuardTest {
    private val drawing = arrayOf(StackTraceElement("android.graphics.HardwareRenderer", "nSyncAndDrawFrame", null, -2))
    private val idle = arrayOf(StackTraceElement("android.os.MessageQueue", "nativePollOnce", null, -2))

    @Test
    fun thePolicy() {
        assertEquals(Act.NONE, DrawGuard.decide(3_000, drawing = true, inGame = true, closed = false))
        assertEquals(Act.CLOSE, DrawGuard.decide(4_000, drawing = true, inGame = true, closed = false))
        assertEquals("stuck elsewhere: Android's own freeze handling", Act.NONE, DrawGuard.decide(8_000, drawing = false, inGame = true, closed = false))
        assertEquals("the hub is not a game", Act.NONE, DrawGuard.decide(8_000, drawing = true, inGame = false, closed = false))
        assertEquals(Act.NONE, DrawGuard.decide(9_500, drawing = true, inGame = true, closed = true))
        assertEquals(Act.END, DrawGuard.decide(10_000, drawing = true, inGame = true, closed = true))
        assertEquals("once closed, where it is stuck does not matter", Act.END, DrawGuard.decide(12_000, drawing = false, inGame = false, closed = true))
    }

    @Test
    fun aGameStuckDrawingIsClosedOnceAndTheAppEndsOnlyIfStillStuck() {
        val game = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var told = 0; var ended = 0
        val g = DrawGuard(front = { game }, isGame = { true }, afterClose = { told++ }, end = { ended++ })
        g.onStuck(3_500, drawing)
        assertFalse(game.isFinishing)
        g.onStuck(4_000, drawing)
        assertTrue("closed from the watchdog's thread", game.isFinishing)
        assertEquals(1, told)
        g.onStuck(4_500, drawing); g.onStuck(9_500, drawing)
        assertEquals("closed once", 1, told); assertEquals(0, ended)
        g.onStuck(10_000, idle)
        assertEquals("still stuck ten seconds in: the app ends itself", 1, ended)
        assertTrue(Flight.tail(5).contains("drawing stuck for 4000 ms in a game: closed it"))
    }

    @Test
    fun aFreeThreadStartsOver() {
        var closes = 0
        val first = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val g = DrawGuard(front = { first }, isGame = { true }, afterClose = { closes++ }, end = {})
        g.onStuck(4_000, drawing); g.onRecover()
        g.onStuck(4_000, drawing)
        assertEquals("a new freeze is judged afresh", 2, closes)
    }
}
