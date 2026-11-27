package app.couch.alhasapa.keyboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

    /** Touch mode, with a made-up game's picture (colours and a handle, as a game played by dragging has). */
    @Test fun touchMode() {
        val w = 960; val h = 540
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        cv.drawColor(android.graphics.Color.rgb(250, 246, 238))
        val hues = intArrayOf(0xFFE53935.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(), 0xFF00ACC1.toInt(), 0xFF3949AB.toInt(), 0xFF8E24AA.toInt(), 0xFFE53935.toInt())
        p.shader = android.graphics.LinearGradient(120f, 0f, 840f, 0f, hues, null, android.graphics.Shader.TileMode.CLAMP)
        cv.drawRoundRect(120f, 300f, 840f, 360f, 30f, 30f, p)
        p.shader = null; p.color = android.graphics.Color.rgb(46, 125, 160)
        cv.drawRoundRect(330f, 80f, 630f, 260f, 24f, 24f, p)
        p.color = android.graphics.Color.WHITE; cv.drawCircle(520f, 330f, 34f, p)
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 6f; p.color = android.graphics.Color.DKGRAY; cv.drawCircle(520f, 330f, 34f, p)
        val jpeg = java.io.ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
        var shown = false
        val watching = object : KeySink by sink { override fun frameShown(n: Int) { shown = true } }
        rule.setContent {
            AlHasapaTheme {
                KeyboardModeScreen(KeyClient.State.Connected("MiTV", "Dialed", touch = true), watching, onGame = {}, frame = KeyClient.MirrorFrame(1, w, h, jpeg))
            }
        }
        val until = System.currentTimeMillis() + 5_000
        while (!shown && System.currentTimeMillis() < until) { rule.waitForIdle(); Thread.sleep(10) }
        rule.onNode(androidx.compose.ui.test.hasTestTag(MIRROR_TAG)).performTouchInput { down(Offset(width * 0.55f, height * 0.6f)); moveBy(Offset(20f, 0f)) }
        save("phone-touch-mode.png")
    }

    private val pieces by lazy { app.couch.alhasapa.data.PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText()) }
    private val people = listOf(app.couch.alhasapa.data.Person("a", "סטיב", "", "", 0), app.couch.alhasapa.data.Person("b", "רוני", "heart", "teal", 0))

    private fun saveDialog(name: String) {
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onNode(androidx.compose.ui.test.isDialog()).captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** "Who holds this phone": asked the first time, with the name, the shape and the colour. */
    @Test fun meDialog() {
        rule.setContent {
            AlHasapaTheme { KeyboardModeScreen(KeyClient.State.Connected("MiTV", "עמוד הבית"), sink, onGame = {}, people = people, me = null, pieces = pieces) }
        }
        rule.onNode(hasContentDescription("רוני")).performClick()
        saveDialog("phone-me.png")
    }

    /** A note for the other one, with a drawing. */
    @Test fun noteDialog() {
        rule.setContent {
            AlHasapaTheme {
                KeyboardModeScreen(
                    KeyClient.State.Connected("MiTV", "Krillion"), sink, onGame = {}, people = people, me = "b", pieces = pieces,
                    noteGames = listOf(NoteGame("krillion", "Krillion"), NoteGame("wordle", "Wordle"), NoteGame("dialed", "Dialed · צבע")),
                )
            }
        }
        rule.onNode(hasContentDescription("השארת פתק")).performClick()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("ידעתי שתנצח 😉")
        rule.onNode(androidx.compose.ui.test.hasTestTag(DOODLE_TAG)).performTouchInput {
            down(Offset(width * 0.3f, height * 0.4f)); moveBy(Offset(60f, -40f)); moveBy(Offset(60f, 40f)); moveBy(Offset(-60f, 80f)); moveBy(Offset(-60f, -80f)); up()
        }
        saveDialog("phone-note.png")
    }

    /** 1.12: the drawing at the size of the whole screen, sideways as in keyboard mode: the pens beside the paper. */
    @Test fun doodleEditor() {
        val drawing = DoodleSamples.state().apply { color = app.couch.alhasapa.data.Ink.COLORS.indexOf("blue"); width = 2 }
        rule.setContent { AlHasapaTheme { DoodleEditor(drawing, pieces.styleFor(null, "b").accent) {} } }
        saveDialog("phone-doodle-editor.png")
    }

    @Test fun pairing() { show(KeyClient.State.NeedCode("MiTV", left = 2)); save("phone-keyboard-code.png") }
    @Test fun notFound() { show(KeyClient.State.NotFound(KeyClient.NO_TV)); save("phone-keyboard-notfound.png") }
    @Test fun loginsDialog() {
        val withLogins = object : KeySink by sink {
            override fun logins() = listOf(
                app.couch.alhasapa.remote.LoginHandoff.Item("nyt", "NYT Games", signedIn = true),
                app.couch.alhasapa.remote.LoginHandoff.Item("hz", "הארץ משחקים", signedIn = null),
            )
        }
        rule.setContent {
            AlHasapaTheme {
                KeyboardModeScreen(KeyClient.State.Connected("MiTV", "עמוד הבית"), withLogins, onGame = {}, handoff = KeyClient.Handoff.Done("nyt", 6))
            }
        }
        rule.onNode(androidx.compose.ui.test.hasText("התחברות לטלוויזיה")).performClick()
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        // The top strip with the button, and the dialog (its own window).
        val img = rule.onNode(androidx.compose.ui.test.isDialog()).captureToImage().asAndroidBitmap()
        File(dir, "phone-logins-dialog.png").outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
