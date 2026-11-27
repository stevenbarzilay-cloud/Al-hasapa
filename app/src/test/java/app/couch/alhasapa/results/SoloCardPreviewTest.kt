package app.couch.alhasapa.results

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import app.couch.alhasapa.ui.theme.Palette
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the solo card (1.11) to PNGs (build/previews), over a game sideways like on the phone: sealed, and the reveal. Not an assertion test. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w914dp-h411dp-land-xxhdpi")
class SoloCardPreviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val pieces by lazy { PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText()) }
    private val steve get() = SoloLine("a", "סטיב", pieces.styleFor(null, "a"), "4/6", sealed = false, hasResult = true)
    private val roni get() = SoloLine("b", "רוני", pieces.styleFor(null, "b"), "2/6", sealed = false, hasResult = true)

    private fun show(o: SoloOutcome, other: String?) = rule.setContent {
        AlHasapaTheme {
            Box(Modifier.fillMaxSize().background(Palette.Ink.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                SoloCard(o, other, reduceMotion = false, onDone = {})
            }
        }
    }

    private fun save(name: String) {
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(3_000); rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun sealed() { show(SoloOutcome("Bandle", roni, emptyList(), null), "סטיב"); save("phone-solo-sealed.png") }

    @Test fun reveal() { show(SoloOutcome("Bandle", roni, listOf(steve), "b"), "סטיב"); save("phone-solo-reveal.png") }
}
