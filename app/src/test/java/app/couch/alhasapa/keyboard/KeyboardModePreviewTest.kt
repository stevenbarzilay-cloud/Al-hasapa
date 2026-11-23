package app.couch.alhasapa.keyboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

/** Draws keyboard mode to PNGs (build/previews), sideways like on the phone, so the look can be checked without one. Not an assertion test. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w914dp-h411dp-land-xxhdpi")
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
    private val tab = hasContentDescription("משטח מגע לכפה: גוררים מהקצה הימני או נוגעים כדי לפתוח")

    private fun show(state: KeyClient.State) = rule.setContent { AlHasapaTheme { KeyboardModeScreen(state, sink, onGame = {}) } }

    private fun save(name: String) {
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun connected() { show(KeyClient.State.Connected("MiTV", "Krillion")); save("phone-keyboard-connected.png") }

    @Test fun pulling() {
        show(KeyClient.State.Connected("MiTV", "Krillion"))
        rule.onNode(tab).performTouchInput { down(center); moveBy(Offset(-120f, 0f), 30); moveBy(Offset(-200f, 0f), 30) }
        rule.mainClock.advanceTimeBy(200)
        save("phone-keyboard-pulling.png")
    }

    @Test fun touchpadOut() {
        show(KeyClient.State.Connected("MiTV", "Krillion"))
        rule.onNode(tab).performClick()
        rule.mainClock.advanceTimeBy(1_500)
        save("phone-keyboard-touchpad.png")
    }

    @Test fun hebrew() {
        show(KeyClient.State.Connected("MiTV", "עמוד הבית"))
        rule.onNode(hasContentDescription("עב")).performClick()
        save("phone-keyboard-hebrew.png")
    }

    @Test fun pairing() { show(KeyClient.State.NeedCode("MiTV", left = 2)); save("phone-keyboard-code.png") }
    @Test fun notFound() { show(KeyClient.State.NotFound(KeyClient.NO_TV)); save("phone-keyboard-notfound.png") }
}
