package app.couch.alhasapa.couches

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the first screen of a new device (2.0) to PNGs (build/previews): on a phone, and on a TV. Not an assertion test. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w411dp-h891dp-port-xxhdpi")
class WelcomePreviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun phone() {
        rule.setContent { AlHasapaTheme { WelcomeScreen(tv = false, tvName = "", onCreate = { _, _, _ -> }, onJoin = {}) } }
        save("phone-welcome.png")
        rule.onNode(hasTestTag(WELCOME_CREATE_TAG)).performClick()
        rule.onNode(hasTestTag(SETUP_A_TAG)).performTextInput("נועה")
        rule.onNode(hasTestTag(SETUP_B_TAG)).performTextInput("יואב")
        save("phone-welcome-setup.png")
    }

    @Test
    @Config(sdk = [37], qualifiers = "w960dp-h540dp-television-xhdpi")
    fun tv() {
        rule.setContent { AlHasapaTheme { WelcomeScreen(tv = true, tvName = "MiTV-AFKR0", onCreate = { _, _, _ -> }, onJoin = {}) } }
        save("tv-welcome.png")
        rule.onNode(hasTestTag(WELCOME_JOIN_TAG)).performClick()
        save("tv-welcome-join.png")
    }
}
