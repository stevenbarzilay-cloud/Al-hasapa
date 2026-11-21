package app.couch.alhasapa.diag

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The startup log must never show on the loading screen or over the hub: it appears only when a problem is recorded. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class DiagOverlayTest {
    @Before fun clean() = Diag.reset()

    private fun overlayOf(activity: ComponentActivity): TextView {
        fun walk(v: View): TextView? = when (v) {
            is TextView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { walk(v.getChildAt(it)) }
            else -> null
        }
        return walk(activity.window.decorView)!!
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun staysInvisibleWhileEverythingIsFine_evenAsTheLogGrows() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        DiagOverlay.install(activity)
        val overlay = overlayOf(activity)
        for (line in listOf("activity: onCreate", "splash: shown", "webview: page finished", "page says: ok")) Diag.log(line)
        Diag.markHubVisible()
        idle()
        assertEquals("the startup log must not be visible on a healthy start", View.GONE, overlay.visibility)
        assertEquals("nothing for the blank check to skip", 0, Diag.overlayBottomPx)
    }

    @Test
    fun appearsWithTheEvidenceOnceAProblemIsRecorded() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        DiagOverlay.install(activity)
        val overlay = overlayOf(activity)
        Diag.log("webview: page finished")
        Diag.markProblem("the window is blank although the page says it drew itself")
        idle()
        assertEquals(View.VISIBLE, overlay.visibility)
        assertNotNull(overlay.text)
        assertTrue(overlay.text.contains("the window is blank"))
    }
}
