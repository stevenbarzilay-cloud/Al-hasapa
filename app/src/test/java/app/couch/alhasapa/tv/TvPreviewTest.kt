package app.couch.alhasapa.tv

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import app.couch.alhasapa.ui.theme.Palette
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the TV keyboard and dock to a PNG (build/previews), so the look can be checked without a TV. Not an assertion test. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvPreviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun drawTheKeyboard() {
        val c = TvController(true, 2f)
        c.resize(1920f, 1080f)
        c.openKeyboard()
        c.press(KbKey.Ch("h")); c.press(KbKey.Ch("i"))
        rule.setContent {
            AlHasapaTheme {
                Column(Modifier.fillMaxSize().background(Palette.Cream)) {
                    Box(Modifier.height(300.dp).fillMaxSize())
                    TvKeyboard(c, rowHeight = 40.dp)
                }
            }
        }
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "tv-keyboard.png").outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        org.junit.Assert.assertTrue(File(dir, "tv-keyboard.png").length() > 5_000)
    }

    @Test
    fun drawTheDockWithThePawOn() {
        val c = TvController(true, 2f)
        c.pageNav = object : PageNav {
            override fun enter(dir: Dir?, cb: (PageResult) -> Unit) = cb(PageResult.Moved)
            override fun move(dir: Dir, cb: (PageResult) -> Unit) = cb(PageResult.Moved)
            override fun activate(cb: (PageResult) -> Unit) = cb(PageResult.None)
            override fun blur() {}
            override fun tap(x: Float, y: Float) {}
        }
        rule.setContent {
            AlHasapaTheme {
                TvLayer(c) { Box(Modifier.fillMaxSize().background(Palette.Cream)) }
            }
        }
        rule.waitForIdle()
        c.togglePaw(); c.placeCursor(1100f, 520f)
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "tv-dock-paw.png").outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        org.junit.Assert.assertTrue(File(dir, "tv-dock-paw.png").length() > 5_000)
    }
}
