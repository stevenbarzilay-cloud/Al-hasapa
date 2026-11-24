package app.couch.alhasapa.home

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.tv.TvController
import app.couch.alhasapa.tv.TvLayer
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import java.io.File
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * How long the TV's home takes to answer the remote, in this environment: the whole shelf walked card by card, there and back, three
 * times, each press timed until the screen has settled (the focus moved, the row scrolled, every animation run to its end). Written
 * to build/reports/tv-home-zap-*.txt.
 *
 * Twice, because this is a build machine with no graphics chip and no TV:
 *  - "work": nothing is painted. What is timed is what the app's main thread does for a press: composing, laying out, and issuing the
 *    drawing commands (here for the whole screen in every frame; a TV keeps what did not change). The nearest thing to the box's
 *    own share of a press.
 *  - "painted": every frame is also painted in software, every cushion from its shapes, every time. A TV's graphics chip does that
 *    part, and keeps each cushion as a finished picture between frames; a machine without one pays for all of it, in every frame.
 *
 * The point is to compare the home before and after a change to how it looks, on the same machine. No assertion on the times.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvHomeZapTimingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val catalog = Catalog.parse(File("src/main/assets/games.json").readText())
    private val pieces = PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
    private val c = TvController(true, 2f)

    private fun press(code: Int): Double {
        val t0 = System.nanoTime()
        rule.runOnIdle { c.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code)); c.handleKey(KeyEvent(KeyEvent.ACTION_UP, code)) }
        rule.waitForIdle()
        return (System.nanoTime() - t0) / 1e6
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun timeTheWorkOfAPress() = walk(painted = false)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun timeAPressWithEveryFramePaintedInSoftware() = walk(painted = true)

    private fun walk(painted: Boolean) {
        val model = HomeModel.from(CouchState.empty(), catalog, pieces, LocalDate.of(2026, 11, 23))
        val nav = HomeNav()
        rule.setContent {
            AlHasapaTheme { TvLayer(c) { TvHome(model, phoneConnected = true, nav = nav, onPlay = {}, onAccounts = {}, onMore = {}, onInfo = {}) } }
        }
        rule.waitForIdle()
        val steps = model.tonight.size - 1
        // Once to warm up (classes loaded, code compiled), then three timed rounds.
        repeat(steps) { press(KeyEvent.KEYCODE_DPAD_LEFT) }; repeat(steps) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        val times = ArrayList<Double>()
        repeat(3) {
            repeat(steps) { times += press(KeyEvent.KEYCODE_DPAD_LEFT) }
            repeat(steps) { times += press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        }
        // Rows: down to the other games and back up, a few times.
        val rows = ArrayList<Double>()
        repeat(6) { rows += press(KeyEvent.KEYCODE_DPAD_DOWN); rows += press(KeyEvent.KEYCODE_DPAD_UP) }
        // Painting the whole screen once more, as a picture.
        val draws = ArrayList<Double>()
        if (painted) repeat(12) { i ->
            press(if (i < 6) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            val t0 = System.nanoTime()
            rule.onRoot().captureToImage().asAndroidBitmap()
            draws += (System.nanoTime() - t0) / 1e6
        }
        fun line(name: String, v: List<Double>): String {
            val s = v.sorted()
            fun at(q: Double) = s[((s.size - 1) * q).toInt()]
            return String.format(Locale.US, "%-34s %4d times: median %6.1f ms, 9 in 10 under %6.1f ms, mean %6.1f ms", name, s.size, at(0.5), at(0.9), s.average())
        }
        val report = listOfNotNull(
            "the TV's home under the remote, ${if (painted) "every frame painted in software" else "the work of a press, nothing painted"} " +
                "(${model.tonight.size} games on the shelf, ${model.more.size} more)",
            line("a press along the shelf", times),
            line("a press between the rows", rows),
            if (painted) line("painting the whole screen once", draws) else null,
        ).joinToString("\n")
        File("build/reports").mkdirs()
        File("build/reports/tv-home-zap-${if (painted) "painted" else "work"}.txt").writeText(report + "\n")
        println(report)
        assertTrue(times.size == steps * 6)
    }
}
