package app.couch.alhasapa.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
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
}
