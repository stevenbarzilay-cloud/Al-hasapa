package app.couch.alhasapa.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.remote.KeyClient
import app.couch.alhasapa.remote.KeyLink
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Touch mode with the phone held upright (2.0.2): the TV's game over the whole screen, and the way back on its side. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], qualifiers = "w411dp-h914dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UprightTouchTest {
    @get:Rule val rule = createComposeRule()
    private val sent = ArrayList<String>()
    /** Each shape the TV was told (0: on its side), and the fingers on the picture. */
    private val shapes = ArrayList<Float>()
    private val touches = ArrayList<Offset>()
    private val uprights = hashMapOf("Pips" to true)
    private val orients = ArrayList<Boolean>()
    private val sink = object : KeySink {
        override fun text(s: String) {}
        override fun key(k: KeyLink.Key, a: KeyLink.Action) {}
        override fun code(c: String) {}
        override fun retry() {}
        override fun address(a: String) {}
        override fun permission() {}
        override fun mirror(on: Boolean, width: Int, crop: KeyLink.Crop?, up: Float) { sent += "mirror:$on"; if (on) shapes += up }
        override fun touch(a: KeyLink.TouchAction, x: Float, y: Float) { sent += "touch:${a.name.lowercase()}"; touches += Offset(x, y) }
        override fun saveResult() { sent += "save-result" }
        override fun touchUpright(game: String) = uprights[game] ?: false
        override fun setTouchUpright(game: String, upright: Boolean) { uprights[game] = upright }
        override fun orient(upright: Boolean) { orients += upright }
    }
    private var state by mutableStateOf<KeyClient.State>(KeyClient.State.Connected("MiTV", "Pips", touch = true))
    private var frame by mutableStateOf<KeyClient.MirrorFrame?>(null)
    private var game = 0

    private fun show() = rule.setContent { AlHasapaTheme { KeyboardModeScreen(state, sink, onGame = { game++ }, frame = frame) } }

    /** A picture as the TV sends them: a JPEG, here of a phone-shaped game (430 x 896). */
    private fun jpeg(w: Int, h: Int): ByteArray {
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(90, 140, 200)) }
        return java.io.ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
    }

    @Test
    fun aGameKeptUprightFillsThePhoneAndTheTvIsToldItsShape() {
        show()
        rule.waitForIdle()
        assertEquals("upright, as kept for Pips", true, orients.last())
        rule.onNodeWithTag(UPRIGHT_TOUCH_TAG).assertIsDisplayed()
        rule.onNodeWithTag(MIRROR_TAG).assertIsDisplayed()
        val up = shapes.last()
        assertTrue("the TV is told a phone's shape: $up", up in 0.35f..0.6f)
        // The picture takes nearly all of the screen: the whole width, and all of the height but the switch above it.
        frame = KeyClient.MirrorFrame(1, 430, (430 / up).toInt(), jpeg(430, (430 / up).toInt()))
        rule.waitForIdle()
        val root = rule.onRoot().getBoundsInRoot()
        val pad = rule.onNodeWithTag(MIRROR_TAG).getBoundsInRoot()
        assertTrue("as wide as the screen but a margin", (pad.right - pad.left).value >= (root.right - root.left).value - 24f)
        assertTrue("and most of its height", (pad.bottom - pad.top).value >= (root.bottom - root.top).value * 0.85f)
        // A finger on the picture's middle is a finger on the game's middle.
        rule.onNodeWithTag(MIRROR_TAG).performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(listOf("touch:down", "touch:up"), sent.filter { it.startsWith("touch:") })
        assertEquals(0.5f, touches.first().x, 0.03f); assertEquals(0.5f, touches.first().y, 0.03f)
    }

    @Test
    fun theEndScreensButtonIsThereUprightTooAndTheShapeStaysTheSame() {
        show()
        rule.waitForIdle()
        val before = shapes.last()
        state = KeyClient.State.Connected("MiTV", "Pips", touch = true, result = true)
        rule.waitForIdle()
        rule.onNodeWithText("🏁 המשחק נגמר: לשמור את התוצאה בטלוויזיה").performClick()
        assertEquals(listOf("save-result"), sent.filter { it == "save-result" })
        assertEquals("the game's shape on the TV did not change for the button", before, shapes.last())
    }

    @Test
    fun turnedOnItsSideAgainItIsKeptSoAndTheTvHearsIt() {
        show()
        rule.waitForIdle()
        rule.onNodeWithText("📱 לרוחב").performClick()
        rule.waitForIdle()
        assertEquals(false, uprights["Pips"])
        assertEquals(false, orients.last())
        assertEquals("on its side: no shape told", 0f, shapes.last())
        // Back on the sideways screen of touch mode, with the button that turns it upright.
        rule.onNodeWithText("📱 לאורך").assertIsDisplayed()
        // Playing on the phone, from the switch on top, is still one press away.
        rule.onNodeWithText("📱 לאורך").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("משחק").performClick()
        assertEquals(1, game)
    }
}
