package app.couch.alhasapa.keyboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.remote.KeyClient
import app.couch.alhasapa.remote.KeyLink
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws keyboard mode to PNGs (build/previews), so the look can be checked without a phone. Not an assertion test. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w411dp-h560dp-xxhdpi")
class KeyboardModePreviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val sink = object : KeySink {
        override fun text(s: String) {}
        override fun key(k: KeyLink.Key, a: KeyLink.Action) {}
        override fun code(c: String) {}
        override fun retry() {}
        override fun address(a: String) {}
        override fun permission() {}
    }

    private fun draw(state: KeyClient.State, name: String) {
        rule.setContent { AlHasapaTheme { KeyboardModeScreen(state, sink, onGame = {}) } }
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun connected() = draw(KeyClient.State.Connected("MiBOX4", "Krillion"), "phone-keyboard-connected.png")
    @Test fun pairing() = draw(KeyClient.State.NeedCode("MiBOX4", left = 2), "phone-keyboard-code.png")
    @Test fun notFound() = draw(KeyClient.State.NotFound(KeyClient.NO_TV), "phone-keyboard-notfound.png")

    @Test
    fun touchpadOut() {
        rule.setContent { AlHasapaTheme { KeyboardModeScreen(KeyClient.State.Connected("MiBOX4", "Krillion"), sink, onGame = {}) } }
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("משטח מגע לכפה: גוררים מהקצה הימני או נוגעים כדי לפתוח")).performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "phone-keyboard-touchpad.png").outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
