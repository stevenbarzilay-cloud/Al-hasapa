package app.couch.alhasapa.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.diag.Flight
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class LightDrawingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aSiteIsDrawnTheLightWayOnThisDeviceUntilTurnedBack() {
        LightDrawing.set(context, "www.Krillion.io", true)
        assertTrue(LightDrawing.isOn(context, "krillion.io"))
        assertTrue("with or without www", LightDrawing.isOn(context, "www.krillion.io"))
        assertFalse(LightDrawing.isOn(context, "www.nytimes.com"))
        assertFalse(LightDrawing.isOn(context, null))
        LightDrawing.set(context, "krillion.io", false)
        assertFalse(LightDrawing.isOn(context, "krillion.io"))
        val state = java.io.File(context.filesDir, "state.json")
        assertFalse("kept on the device, not in the state a backup carries", state.isFile && state.readText().contains("krillion"))
    }

    @Test
    fun theHostOfAnAddress() {
        assertEquals("krillion.io", hostOf("https://krillion.io/play?day=12"))
        assertEquals("www.nytimes.com", hostOf("https://www.nytimes.com/games/wordle/index.html"))
        assertNull(hostOf("not an address"))
        assertNull(hostOf(null))
    }
    private fun previousRun(log: String) {
        Flight.drainForTests()
        File(context.filesDir, "flight-previous.txt").delete()
        File(context.filesDir, "flight.txt").writeText(log.trimIndent())
        Flight.start(context)
    }

    @Test
    fun aGameThatHeldThePictureInAnEarlierRunOpensLightAndTurningItBackIsKept() {
        // The 23 November report, as the flight recorder wrote it.
        previousRun(
            """
            15:45:50.407 PlayerActivity in front
            15:45:52.968 player: page loaded (krillion.io)
            15:45:58.318 MAIN THREAD STUCK for 2998 ms, at:
                at android.graphics.HardwareRenderer.nSyncAndDrawFrame(Native Method)
                at android.graphics.HardwareRenderer.syncAndDrawFrame(HardwareRenderer.java:433)
            15:46:15.960 PlayerActivity paused
            15:46:16.050 main thread free again after 20643 ms
            """,
        )
        LightDrawing.fromEarlierFreezes(context)
        assertTrue(LightDrawing.isOn(context, "krillion.io"))
        LightDrawing.set(context, "krillion.io", false)            // turned back to full drawing in the menu
        Flight.drainForTests()
        Flight.start(context)                                     // the freeze is now in the run before the previous one
        LightDrawing.fromEarlierFreezes(context)
        assertFalse("the same freeze is acted on once", LightDrawing.isOn(context, "krillion.io"))
    }

    @Test
    fun aShortWaitOrOneAfterTheGameWasClosedIsNothing() {
        previousRun(
            """
            20:00:01.000 player: page loaded (www.nytimes.com)
            20:00:05.000 MAIN THREAD STUCK for 2600 ms, at:
                at android.graphics.HardwareRenderer.nSyncAndDrawFrame(Native Method)
            20:00:05.400 main thread free again after 2900 ms
            20:01:00.000 player: page loaded (krillion.io)
            20:01:02.000 PlayerActivity closed
            20:01:09.000 MAIN THREAD STUCK for 2600 ms, at:
                at android.graphics.HardwareRenderer.nSyncAndDrawFrame(Native Method)
            """,
        )
        LightDrawing.fromEarlierFreezes(context)
        assertFalse("under four seconds", LightDrawing.isOn(context, "nytimes.com"))
        assertFalse("the game was closed before it", LightDrawing.isOn(context, "krillion.io"))
    }
}
