package app.couch.alhasapa.diag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A crash report that fits one photograph. The crash of 1.9.0 (a function calling itself until the stack ran out) came as nine
 * screenshots of the same two lines, and none of them showed which version of the app it was.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class CrashReportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The crash of 1.9.0 as it looked: the two frames, five hundred times, under the frames that led there. */
    private fun overflow(): Throwable {
        val circle = listOf(
            StackTraceElement("android.app.Activity", "runOnUiThread", "Activity.java", 8342),
            StackTraceElement("app.couch.alhasapa.diag.Flight\$\$ExternalSyntheticLambda2", "run", "r8-map-id-d6be3fcf", 233),
        )
        val tail = listOf(
            StackTraceElement("app.couch.alhasapa.bridge.HubBridge", "play", "r8-map-id-d6be3fcf", 51),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 317),
        )
        return StackOverflowError("stack size 8188KB").apply { stackTrace = (List(500) { circle }.flatten() + tail).toTypedArray() }
    }

    @Test
    fun aStackThatGoesRoundInCirclesIsSaidOnce() {
        val report = CrashReport.report(context, Thread.currentThread(), overflow())
        val stack = report.substringAfter("--- stack ---\n").substringBefore("--- just before ---").trim().lines()
        assertEquals(
            listOf(
                "java.lang.StackOverflowError: stack size 8188KB",
                "\tat android.app.Activity.runOnUiThread(Activity.java:8342)",
                "\tat app.couch.alhasapa.diag.Flight\$\$ExternalSyntheticLambda2.run(r8-map-id-d6be3fcf:233)",
                "\t… the 2 lines above, 500 times in a row",
                "\tat app.couch.alhasapa.bridge.HubBridge.play(r8-map-id-d6be3fcf:51)",
                "\tat android.os.Looper.loop(Looper.java:317)",
            ),
            stack,
        )
        assertTrue("and the report still ends with what happened just before", report.contains("--- just before ---"))
    }

    @Test
    fun theVersionAndWhatHappenedAreTheHeadline() {
        val report = CrashReport.report(context, Thread.currentThread(), overflow())
        val head = CrashReport.headline(report)
        assertTrue(head, Regex("^app \\d+\\.\\d+\\.\\d+(-tv)? \\((debug|release)\\) · java\\.lang\\.StackOverflowError: stack size 8188KB$").matches(head))
        // A report of a run that stopped without a crash has no stack: its first line says what happened.
        assertEquals(
            "app 1.9.3-tv (release) · stopped while in use; last seen Mon Nov 23 21:04:11",
            CrashReport.headline("stopped while in use; last seen Mon Nov 23 21:04:11\napp 1.9.3-tv (release), android 11 (api 30), Xiaomi MiTV-AFKR0\n--- what happened"),
        )
        assertEquals("", CrashReport.headline(""))
    }

    @Test
    fun onlyRealCirclesAreFolded() {
        val plain = listOf("a", "b", "c", "a", "b", "d", "x", "x")
        assertEquals("twice is not a circle", plain, CrashReport.fold(plain))
        assertEquals(listOf("top", "x", "\t… the line above, 3 times in a row", "end"), CrashReport.fold(listOf("top", "x", "x", "x", "end")))
        assertEquals(
            listOf("a", "b", "c", "\t… the 3 lines above, 4 times in a row", "a", "b"),
            CrashReport.fold(List(4) { listOf("a", "b", "c") }.flatten() + listOf("a", "b")),
        )
        assertEquals(emptyList<String>(), CrashReport.fold(emptyList()))
    }
}
