package app.couch.alhasapa.diag

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The TV box going to standby: the game closes, the hub steps behind the home screen, the phone link stops, all of it written down. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class StandbyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun whenTheScreenGoesOffTheGameClosesTheHubStepsBackAndThePhoneLinkStops() {
        val hub = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val game = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var back: Activity? = null
        var stopped = 0
        val s = Standby(screens = { listOf(hub, game) }, isHub = { it === hub }, stopKeys = { stopped++ }, back = { back = it })
        s.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))
        assertTrue("no page runs while the box sleeps", game.isFinishing)
        assertFalse("the hub stays, behind the home screen", hub.isFinishing)
        assertSame(hub, back)
        assertEquals(1, stopped)
        assertTrue(Flight.tail(3).contains("screen off (standby)"))
        s.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        assertTrue(Flight.tail(3).contains("screen on (the box woke up)"))
        s.onReceive(context, Intent(Intent.ACTION_DREAMING_STARTED))
        assertTrue(Flight.tail(3).contains("screensaver on"))
    }

    @Test
    fun itHearsTheSystemsScreenBroadcasts() {
        Standby.install(context as android.app.Application, { emptyList() }, { false })
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Flight.tail(5).contains("screen off (standby)"))
    }

    @Test
    fun theScreenIsDescribedPlainly() {
        assertEquals("1920x1080 at 60 Hz, on", Standby.describe(1920, 1080, 59.94f, android.view.Display.STATE_ON))
        assertEquals("1920x1080 at 60 Hz, off", Standby.describe(1920, 1080, 60f, android.view.Display.STATE_OFF))
    }
}
