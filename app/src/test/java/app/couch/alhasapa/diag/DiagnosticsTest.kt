package app.couch.alhasapa.diag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.data.CouchState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class DiagnosticsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aCrashIsWrittenDownWithTheVersionsTheTimingsAndTheStackAndShownOnce() {
        CrashReport.clear(context)
        assertNull(CrashReport.pending(context))
        Startup.mark("test mark")
        val text = CrashReport.report(context, Thread.currentThread(), IllegalStateException("boom"))
        assertTrue(text.contains("IllegalStateException: boom")); assertTrue(text.contains("test mark:")); assertTrue(text.contains("app "))
        assertTrue("it holds nothing about the person", !text.contains("סטיב") && !text.contains("@"))
        java.io.File(context.filesDir, "last-crash.txt").writeText(text)
        assertEquals(text, CrashReport.pending(context))
        CrashReport.clear(context)
        assertNull("once continued, it is gone", CrashReport.pending(context))
    }

    @Test
    fun theHandlerThatWritesTheCrashIsInstalledAndPassesTheCrashOn() {
        CrashReport.clear(context)
        CrashReport.install(context as android.app.Application)
        val h = Thread.getDefaultUncaughtExceptionHandler()!!
        // The previous handler (the test runner's) is called afterwards; swallow it so the test does not die.
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        CrashReport.install(context)
        runCatching { Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("handler")) }
        assertNotNull(CrashReport.pending(context)); assertTrue(CrashReport.pending(context)!!.contains("RuntimeException: handler"))
        CrashReport.clear(context); Thread.setDefaultUncaughtExceptionHandler(h)
    }

    @Test
    fun startupMarksAreKeptInOrderAndSaved() {
        Startup.mark("first"); Startup.mark("second")
        val s = Startup.summary()
        assertTrue(s.indexOf("first:") < s.indexOf("second:")); assertTrue(s.contains(" ms"))
        Startup.save(context)
        assertTrue(Startup.last(context)!!.contains("second:"))
        assertTrue(Startup.environment(context).contains("android"))
    }

    @Test
    fun theCatalogueOfTheAppGrowsWhenAGameIsAddedAndNotBefore() {
        val app = context as CouchApp
        val before = app.catalog.games.size
        assertNull(app.catalog.game("c42"))
        app.repo.update { it.put("custom", JSONArray().put(JSONObject().put("id", "c42").put("n", "New").put("s", "my").put("k", "ידע").put("m", "N").put("u", "https://new.example/x").put("metric", "none").put("lib", 1))) }
        assertEquals(before + 1, app.catalog.games.size)
        assertNotNull(app.catalog.game("c42"))
        assertTrue("and it is on the shelf", app.catalog.inLib(app.catalog.game("c42")!!, app.repo.snapshot()))
        // the hub's bridge asks for the catalogue each time, so it sees the new game
        val hub = JSONObject(app.catalog.toHubJson(app.repo.snapshot()))
        assertTrue((0 until hub.getJSONArray("games").length()).any { hub.getJSONArray("games").getJSONObject(it).getString("id") == "c42" })
        assertFalse(CouchState.empty().has("custom"))
    }
    @Test
    fun theReportShowsTheScreenAndOnlyThePreviousRunsOwnEnding() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val now = System.currentTimeMillis()
        Flight.drainForTests()
        java.io.File(context.filesDir, "run.txt").writeText((now - 10_000).toString())     // the previous run started ten seconds ago
        java.io.File(context.filesDir, "session.txt").writeText("back 15:46:17.257 PlayerActivity")
        java.io.File(context.filesDir, "flight.txt").writeText("15:46:21.272 phone keyboard: stopped")
        // A freeze two hours earlier belongs to an older run (the 23 Nov report showed one as the previous run's ending).
        shadowOf(am).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder().setProcessName(context.packageName).setTimestamp(now - 7_200_000)
                .setReason(android.app.ApplicationExitInfo.REASON_ANR).build(),
        )
        Flight.start(context)
        var t = Diagnostics.text(context)
        assertTrue(t, t.contains("screen: "))
        assertFalse(t, t.contains("froze (ANR)"))
        assertTrue(t, t.contains("android has no record of the app ending"))
        // One from inside the previous run is shown.
        shadowOf(am).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder().setProcessName(context.packageName).setTimestamp(now - 2_000)
                .setReason(android.app.ApplicationExitInfo.REASON_LOW_MEMORY).build(),
        )
        t = Diagnostics.text(context)
        assertTrue(t, t.contains("android says the app ended: out of memory"))
        assertFalse(t, t.contains("no record"))
    }
}
