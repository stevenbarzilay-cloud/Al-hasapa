package app.couch.alhasapa.keyboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.data.Person
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the phone home's dialogs (1.10) to PNGs (build/previews), upright like on the phone, with a friend who comes often and a guest
 * of the evening beside the two of us. Not an assertion test.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w411dp-h891dp-port-xxhdpi")
class HomeDialogsPreviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val pieces by lazy { PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText()) }
    private val people = listOf(
        Person("a", "סטיב", "", "", 0), Person("b", "רוני", "", "", 0),
        Person("u1a2b3c4", "דני", "star", "green", 5),
        Person("u5d6e7f8", "מיה", "heart", "mustard", 6, guest = true, day = "2026-11-27"),
    )

    private fun saveDialog(name: String) {
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Who holds this phone, from the home: the two of us, a friend, a guest, and the two ways to add one. */
    @Test fun meWithOthers() {
        rule.setContent { AlHasapaTheme { IdentityDialog(people, "b", pieces, onSave = {}, onDismiss = {}, today = "2026-11-27") } }
        saveDialog("phone-home-me.png")
    }

    /** A guest being added. */
    @Test fun addingAGuest() {
        rule.setContent { AlHasapaTheme { IdentityDialog(people, "b", pieces, onSave = {}, onDismiss = {}, today = "2026-11-27") } }
        rule.onNodeWithText("+ אורח").performClick()
        saveDialog("phone-home-guest.png")
    }

    /** Together or alone? (1.11), with someone who already played it alone today. */
    @Test fun playMode() {
        rule.setContent {
            AlHasapaTheme {
                app.couch.alhasapa.ui.PlayModeDialog(
                    "Bandle", listOf(app.couch.alhasapa.ui.Challenger("סטיב", pieces.styleFor(null, "a"), true)),
                    onTogether = {}, onSolo = {}, onElsewhere = {}, onDismiss = {},
                )
            }
        }
        saveDialog("phone-play-mode.png")
    }

    /** A game played elsewhere (1.11.1): its result from a photo of the computer's screen, as the sheet shows it once read. */
    @Test fun photoSheet() {
        val game = app.couch.alhasapa.catalog.Catalog.load(rule.activity).game("wordle")!!
        rule.setContent {
            AlHasapaTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(app.couch.alhasapa.ui.theme.Palette.Cream),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    app.couch.alhasapa.results.ResultSheet(
                        support = app.couch.alhasapa.results.ResultSupport(game, app.couch.alhasapa.data.CouchState.empty(), "2026-11-27"),
                        couple = app.couch.alhasapa.results.Couple("סטיב", "רוני", pieces.styleFor(null, "a"), pieces.styleFor(null, "b")),
                        who = "a", initialText = "4/6", captured = false, onSave = {}, onClear = null, onCancel = {},
                        modifier = androidx.compose.ui.Modifier.padding(12.dp), solo = true,
                        onPhoto = {}, photoNote = "📷 קראנו מהתמונה. כדאי לבדוק.",
                    )
                }
            }
        }
        rule.waitForIdle()
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/previews").apply { mkdirs() }, "phone-photo-sheet.png").outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 1.12: the drawing at the size of the whole screen, upright as on the home: the pens under the paper. */
    @Test fun doodleEditorUpright() {
        val drawing = DoodleSamples.state()
        rule.setContent { AlHasapaTheme { DoodleEditor(drawing, pieces.styleFor(null, "b").accent) {} } }
        saveDialog("phone-doodle-editor-upright.png")
    }

    /** A note from the home, for anyone here. */
    @Test fun noteForAnyone() {
        rule.setContent {
            AlHasapaTheme {
                NoteDialog(
                    recipients = people.filter { it.who != "b" }, initialTo = "a", pieces = pieces,
                    color = pieces.styleOf(people[1]).accent, games = listOf(NoteGame("wordle", "Wordle"), NoteGame("krillion", "Krillion")),
                    initialGame = null, onSend = { _, _, _, _, _ -> }, onDismiss = {},
                )
            }
        }
        saveDialog("phone-home-note.png")
    }
}
