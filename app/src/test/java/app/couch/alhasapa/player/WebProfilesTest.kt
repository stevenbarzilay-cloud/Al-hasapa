package app.couch.alhasapa.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.diag.Flight
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The safety net around the WebView's profile code: a device whose WebView got stuck there gets games in the shared profile instead. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class WebProfilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun prefs() = context.getSharedPreferences("webview", Context.MODE_PRIVATE)

    @After fun clean() { WebProfiles.retry(context); WebProfiles.resetForTests() }

    @Test
    fun aMarkLeftByACallThatNeverCameBackTurnsProfilesOff() {
        File(context.filesDir, "profile-call.txt").writeText("getOrCreateProfile games")
        WebProfiles.init(context)
        assertEquals("getOrCreateProfile games", WebProfiles.offBecause)
        assertFalse("off: every site uses the shared profile", WebProfiles.supported())
        assertFalse("the mark is used once", File(context.filesDir, "profile-call.txt").exists())
        // The next start remembers it.
        WebProfiles.init(context)
        assertNotNull(WebProfiles.offBecause)
    }

    @Test
    fun aFreezeInsideTheProfileCodeInTheFlightRecorderTurnsThemOffToo() {
        Flight.drainForTests()
        File(context.filesDir, "flight.txt").writeText("13:36:30.028 PlayerActivity in front")
        File(context.filesDir, "flight-previous.txt").writeText(
            "13:36:29.000 player: page loaded\n13:36:33.100 MAIN THREAD STUCK for 2600 ms, at:\n    at \$Proxy3.getOrCreateProfile(Unknown Source)\n    at androidx.webkit.ProfileStore.getOrCreateProfile(x:13)",
        )
        Flight.start(context)
        assertTrue(Flight.froze("getOrCreateProfile"))
        WebProfiles.init(context)
        assertNotNull(WebProfiles.offBecause)
    }

    @Test
    fun aSlowProfileCallThatCameBackIsNotAFreeze() {
        Flight.drainForTests()
        File(context.filesDir, "flight-previous.txt").delete()
        File(context.filesDir, "flight.txt").writeText(STUCK + "\n13:36:35.300 main thread free again after 4300 ms\n13:36:35.900 player: page loaded")
        Flight.start(context)
        assertFalse("a first profile can be slow on a weak box", Flight.froze("getOrCreateProfile"))
        WebProfiles.init(context)
        assertNull(WebProfiles.offBecause)
    }

    @Test
    fun aRetryByHandIsNotUndoneByTheSameFreezeAtTheNextStart() {
        Flight.drainForTests()
        File(context.filesDir, "flight-previous.txt").delete()
        File(context.filesDir, "flight.txt").writeText(STUCK)
        Flight.start(context)                       // the start right after the freeze
        WebProfiles.init(context)
        assertNotNull(WebProfiles.offBecause)
        WebProfiles.retry(context)
        Flight.drainForTests()
        Flight.start(context)                       // the next start: the freeze is now the run before the previous one
        assertTrue("still in the recorder", Flight.froze("getOrCreateProfile"))
        WebProfiles.init(context)
        assertNull("the retry stands", WebProfiles.offBecause)
        // A new freeze there is acted on again.
        Flight.drainForTests()
        File(context.filesDir, "flight.txt").writeText(STUCK.replace("13:36:33.100", "21:02:07.450"))
        Flight.start(context)
        WebProfiles.init(context)
        assertNotNull(WebProfiles.offBecause)
    }

    @Test
    fun aNewWebViewGetsAnotherChanceAndSoDoesARetryByHand() {
        prefs().edit().putString("profiles_off_for", "157.0.0.1").putString("profiles_off_why", "x").commit()
        WebProfiles.init(context)
        assertNull("this device's WebView is another version now: try again", WebProfiles.offBecause)
        prefs().edit().putString("profiles_off_for", "").putString("profiles_off_why", "x").commit()  // Robolectric's WebView has no version
        WebProfiles.init(context)
        assertNotNull(WebProfiles.offBecause)
        WebProfiles.retry(context)
        assertNull(WebProfiles.offBecause)
        WebProfiles.init(context)
        assertNull("and it stays on", WebProfiles.offBecause)
    }

    private companion object {
        const val STUCK = "13:36:29.000 player: page loaded\n13:36:33.100 MAIN THREAD STUCK for 2600 ms, at:\n" +
            "    at \$Proxy3.getOrCreateProfile(Unknown Source)\n    at androidx.webkit.ProfileStore.getOrCreateProfile(x:13)"
    }

    @Test
    fun nothingIsAskedOfTheProfileCodeForTheSharedProfile() {
        WebProfiles.init(context)
        assertEquals(WebProfiles.DEFAULT, WebProfiles.attach(android.webkit.WebView(context), WebProfiles.DEFAULT))
        assertFalse(File(context.filesDir, "profile-call.txt").exists())
    }
}
