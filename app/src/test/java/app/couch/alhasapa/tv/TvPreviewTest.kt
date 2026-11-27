package app.couch.alhasapa.tv

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.player.PlayerTvDock
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

    /** The TV's sign-in screen: the phone's way first, in three steps, then the accounts themselves. */
    @Test
    fun drawTheAccountsScreen() {
        val c = TvController(true, 2f)
        val catalog = app.couch.alhasapa.catalog.Catalog.parse(File("src/main/assets/games.json").readText())
        val pieces = app.couch.alhasapa.data.PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
        rule.setContent {
            AlHasapaTheme {
                TvLayer(c) {
                    app.couch.alhasapa.accounts.AccountsScreen(
                        sites = catalog.sites.values.filter { it.account }, nameA = "סטיב", nameB = "רוני",
                        pieceA = pieces.styleFor(null, "a"), pieceB = pieces.styleFor(null, "b"),
                        onLogin = { _, _, _ -> }, onChrome = {}, onBack = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "tv-accounts.png").outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        org.junit.Assert.assertTrue(File(dir, "tv-accounts.png").length() > 5_000)
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

    @Test
    fun drawTheHome() {
        val c = TvController(true, 2f)
        val catalog = app.couch.alhasapa.catalog.Catalog.parse(File("src/main/assets/games.json").readText())
        val pieces = app.couch.alhasapa.data.PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
        val date = java.time.LocalDate.of(2026, 11, 23)
        val state = app.couch.alhasapa.data.CouchState.empty().also {
            app.couch.alhasapa.data.CouchState.setEntry(it, app.couch.alhasapa.data.CouchState.dayKey(date), "krillion", org.json.JSONObject().put("w", "").put("t", 1L))
        }
        val model = app.couch.alhasapa.home.HomeModel.from(state, catalog, pieces, date)
        val nav = app.couch.alhasapa.home.HomeNav()
        // As on the TV, where the app reads the logo and the picture while it starts: they are there with the first frame.
        app.couch.alhasapa.home.warmHomeArt(ApplicationProvider.getApplicationContext<Context>())
        rule.setContent {
            AlHasapaTheme {
                TvLayer(c) {
                    app.couch.alhasapa.home.TvHome(model, phoneConnected = true, nav = nav, onPlay = {}, onAccounts = {}, onMore = {}, onInfo = {})
                }
            }
        }
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        fun shot(name: String) {
            val img = rule.onRoot().captureToImage().asAndroidBitmap()
            File(dir, name).outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            org.junit.Assert.assertTrue(File(dir, name).length() > 5_000)
        }
        shot("tv-home.png")
        // Further along the shelf (the row has moved), then the other games.
        repeat(8) { rule.runOnIdle { c.navigate(Dir.LEFT) }; rule.waitForIdle() }
        shot("tv-home-along.png")
        rule.runOnIdle { c.navigate(Dir.DOWN) }; rule.waitForIdle()
        repeat(3) { rule.runOnIdle { c.navigate(Dir.LEFT) }; rule.waitForIdle() }
        shot("tv-home-more.png")
    }

    /**
     * The box gives one of the cushions Android's own focus when the home opens (the remote's focus is the app's, and moves without
     * it). Until 1.9.10 that cushion then wore Android's focus mark, a grey box behind it, for as long as the home was up.
     */
    @Test
    fun aCushionWithAndroidsOwnFocusLooksLikeAnyOther() {
        val c = TvController(true, 2f)
        val catalog = app.couch.alhasapa.catalog.Catalog.parse(File("src/main/assets/games.json").readText())
        val pieces = app.couch.alhasapa.data.PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
        val date = java.time.LocalDate.of(2026, 11, 23)
        val model = app.couch.alhasapa.home.HomeModel.from(app.couch.alhasapa.data.CouchState.empty(), catalog, pieces, date)
        val nav = app.couch.alhasapa.home.HomeNav()
        app.couch.alhasapa.home.warmHomeArt(ApplicationProvider.getApplicationContext<Context>())
        rule.setContent {
            // As on a TV, which is driven by keys, not by touch (Android's focus exists only then).
            val input = androidx.compose.ui.platform.LocalInputModeManager.current
            androidx.compose.runtime.LaunchedEffect(Unit) { input.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            AlHasapaTheme { TvLayer(c) { app.couch.alhasapa.home.TvHome(model, phoneConnected = false, nav = nav, onPlay = {}, onAccounts = {}, onMore = {}, onInfo = {}) } }
        }
        rule.waitForIdle()
        val node = rule.onNode(androidx.compose.ui.test.hasContentDescription("Strands", substring = true))
        node.requestFocus()
        rule.waitForIdle()
        node.assertIsFocused()
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/previews").apply { mkdirs() }
        File(dir, "tv-home-android-focus.png").outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        // Just inside the button's corner, outside the cushion's own outline: the wall behind it, as beside any other cushion.
        val b = node.fetchSemanticsNode().boundsInRoot
        val corner = img.getPixel(b.left.toInt() + 3, b.top.toInt() + 3)
        val outside = img.getPixel(b.left.toInt() - 2, b.top.toInt() + 3)
        // (The wall is a faint gradient: two points side by side may differ by a shade.)
        fun near(a: Int, b: Int) = listOf(16, 8, 0).all { sh -> kotlin.math.abs(((a shr sh) and 0xFF) - ((b shr sh) and 0xFF)) <= 6 }
        org.junit.Assert.assertTrue("no box of Android's own behind the cushion: %08x against %08x".format(corner, outside), near(corner, outside))
    }

    /** The TV's settings over the home, with the picture made a little smaller (the red corners at its edges), and each cursor. */
    @Test
    fun drawTheSettingsAndTheCursors() {
        val c = TvController(true, 2f)
        val catalog = app.couch.alhasapa.catalog.Catalog.parse(File("src/main/assets/games.json").readText())
        val pieces = app.couch.alhasapa.data.PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
        val model = app.couch.alhasapa.home.HomeModel.from(app.couch.alhasapa.data.CouchState.empty(), catalog, pieces, java.time.LocalDate.of(2026, 11, 27))
        app.couch.alhasapa.home.warmHomeArt(ApplicationProvider.getApplicationContext<Context>())
        c.pictureScale = 0.96f
        rule.setContent {
            AlHasapaTheme { TvLayer(c) { app.couch.alhasapa.home.TvHome(model, phoneConnected = false, nav = app.couch.alhasapa.home.HomeNav(), onPlay = {}, onAccounts = {}, onMore = {}, onInfo = {}) } }
        }
        rule.waitForIdle()
        rule.runOnIdle { c.openPanel(TvPanel.SETTINGS) }
        rule.runOnIdle { c.navigate(Dir.DOWN); c.navigate(Dir.DOWN) }
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        fun shot(name: String) {
            val img = rule.onRoot().captureToImage().asAndroidBitmap()
            File(dir, name).outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        shot("tv-settings.png")
        rule.runOnIdle { c.closePanel(); c.togglePaw(); c.cursorShape = CursorShape.ARROW; c.placeCursor(700f, 480f) }
        rule.waitForIdle()
        shot("tv-cursor-arrow.png")
        rule.runOnIdle { c.cursorShape = CursorShape.MOUSE; c.cursorSize = CursorSize.LARGE }
        rule.waitForIdle()
        shot("tv-cursor-mouse.png")
    }

    /** The result sheet on a TV: buttons only, the focus on "save". */
    @Test
    fun drawTheResultSheetOnATv() {
        val c = TvController(true, 2f)
        val catalog = app.couch.alhasapa.catalog.Catalog.parse(File("src/main/assets/games.json").readText())
        val pieces = app.couch.alhasapa.data.PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
        val state = app.couch.alhasapa.data.CouchState.empty()
        val support = app.couch.alhasapa.results.ResultSupport(catalog.game("catfishing")!!, state, "2026-11-27")
        val couple = app.couch.alhasapa.results.Couple("סטיב", "רוני", pieces.styleFor(null, "a"), pieces.styleFor(null, "b"))
        rule.setContent {
            AlHasapaTheme {
                TvLayer(c) {
                    Box(Modifier.fillMaxSize().background(Palette.Ink.copy(alpha = 0.45f)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        app.couch.alhasapa.results.ResultSheet(support, couple, null, "Catfishing #812\n🐟 8/10", captured = true, onSave = {}, onClear = null, onCancel = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "tv-result-sheet.png").outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * 1.11.2: Dialed's "45.11 מתוך 50" was read as 11 of 50, and the TV's − and + could not mend it (what was read won). A number set
     * with the buttons now wins, ten at a time for a score, and stays out of the 50.
     */
    @Test
    fun aMisreadScoreIsMendedWithTheTvsButtonsAndStaysOutOfItsWhole() {
        val c = TvController(true, 2f)
        val catalog = app.couch.alhasapa.catalog.Catalog.parse(File("src/main/assets/games.json").readText())
        val pieces = app.couch.alhasapa.data.PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
        val game = catalog.game("dialed")!!
        val support = app.couch.alhasapa.results.ResultSupport(game, app.couch.alhasapa.data.CouchState.empty(), "2026-11-27")
        val couple = app.couch.alhasapa.results.Couple("סטיב", "רוני", pieces.styleFor(null, "a"), pieces.styleFor(null, "b"))
        var saved: app.couch.alhasapa.results.ResultInput? = null
        rule.setContent {
            AlHasapaTheme {
                TvLayer(c) {
                    app.couch.alhasapa.results.ResultSheet(support, couple, null, "11/50", captured = true, onSave = { saved = it }, onClear = null, onCancel = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasText("11/50", substring = true) and androidx.compose.ui.test.hasText("זוהה", substring = true)).assertExists()
        repeat(3) { rule.onNode(androidx.compose.ui.test.hasContentDescription("עוד 10")).performClick(); rule.waitForIdle() }
        repeat(4) { rule.onNode(androidx.compose.ui.test.hasContentDescription("להגדיל")).performClick(); rule.waitForIdle() }
        rule.onNode(androidx.compose.ui.test.hasText("45/50", substring = true) and androidx.compose.ui.test.hasText("זוהה", substring = true)).assertExists()
        rule.onNode(androidx.compose.ui.test.hasText("שמירה")).performClick(); rule.waitForIdle()
        val input = saved!!
        val d = app.couch.alhasapa.results.ResultDraft.evaluate(game, input.text, input.manual, input.fail, org.json.JSONObject(), "2026-11-27")
        org.junit.Assert.assertEquals(45.0, d.parsed!!.v, 0.0)
        org.junit.Assert.assertEquals(50, d.parsed!!.max)
        org.junit.Assert.assertEquals("what was misread is not kept with it", "", d.result!!.raw)
    }

    /** 1.12: a note with a drawing made with pens, as the TV shows it: the paper beside the words, each stroke in its colour and width. */
    @Test
    fun drawANoteWithAPaintedDrawingOnTheTv() {
        val c = TvController(true, 2f)
        val pieces = app.couch.alhasapa.data.PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
        val sample = app.couch.alhasapa.keyboard.DoodleSamples.state()
        val note = app.couch.alhasapa.data.Note("n9", "b", null, "בוקר טוב, אהוב. השארתי לך שמש.", sample.doodle(), 1_000, ink = sample.inks())
        val view = NoteView(note, "רוני", "סטיב", pieces.styleFor(null, "b"), null)
        rule.setContent { AlHasapaTheme { TvLayer(c) { Box(Modifier.fillMaxSize().background(Palette.Cream)); NoteCard(view) {} } } }
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "tv-note-drawing.png").outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        // Drawn alone, without words: the paper under the names.
        org.junit.Assert.assertEquals(sample.strokes.size, note.ink!!.size)
    }

    /** A note Roni left Steve for after Krillion: a few words and a heart drawn with a finger. */
    @Test
    fun drawANoteOnTheTv() {
        val c = TvController(true, 2f)
        val catalog = app.couch.alhasapa.catalog.Catalog.parse(File("src/main/assets/games.json").readText())
        val pieces = app.couch.alhasapa.data.PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
        val state = app.couch.alhasapa.data.CouchState.empty()
        // A heart, as a finger draws one: two arcs and a point.
        val heart = (0..40).flatMap { i ->
            val t = Math.PI * 2 * i / 40
            val x = 16 * Math.pow(Math.sin(t), 3.0)
            val y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t)
            listOf((500 + x * 14).toInt(), (480 - y * 26).toInt())
        }.toIntArray()
        val note = app.couch.alhasapa.data.Note("p1", "b", "krillion", "ידעתי שתנצח את הקריליון הזה 😉", listOf(heart, intArrayOf(150, 850, 300, 820, 450, 860)), at = 1)
        val view = NoteView.of(note, state, pieces, catalog)
        rule.setContent { AlHasapaTheme { TvLayer(c) { NoteCard(view) {} } } }
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "tv-note.png").outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The home with two other colours chosen (teal and mustard), at the end of the shelf, where the games played this evening are. */
    @Test
    fun drawTheHomeInOtherColours() {
        val c = TvController(true, 2f)
        val catalog = app.couch.alhasapa.catalog.Catalog.parse(File("src/main/assets/games.json").readText())
        val pieces = app.couch.alhasapa.data.PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
        val date = java.time.LocalDate.of(2026, 11, 23)
        val state = app.couch.alhasapa.data.CouchState.empty().also {
            for (id in listOf("wordle", "bandle")) app.couch.alhasapa.data.CouchState.setEntry(it, app.couch.alhasapa.data.CouchState.dayKey(date), id, org.json.JSONObject().put("w", "").put("t", 1L))
            it.put("pieces", org.json.JSONObject()
                .put("a", org.json.JSONObject().put("shape", "circle").put("color", "teal"))
                .put("b", org.json.JSONObject().put("shape", "star").put("color", "mustard")))
        }
        val model = app.couch.alhasapa.home.HomeModel.from(state, catalog, pieces, date)
        val nav = app.couch.alhasapa.home.HomeNav()
        app.couch.alhasapa.home.warmHomeArt(ApplicationProvider.getApplicationContext<Context>())
        rule.setContent {
            AlHasapaTheme { TvLayer(c) { app.couch.alhasapa.home.TvHome(model, phoneConnected = false, nav = nav, onPlay = {}, onAccounts = {}, onMore = {}, onInfo = {}) } }
        }
        rule.waitForIdle()
        // The app's own colours follow the pieces (1.12): the focus's threads, the picture of the two of us.
        val red = Palette.Red; val pink = Palette.Pink
        try {
            rule.runOnIdle { Palette.follow(model.pieceA, model.pieceB) }
            org.junit.Assert.assertEquals(model.pieceA.accent, Palette.Red); org.junit.Assert.assertEquals(model.pieceB.soft, Palette.Pink)
            repeat(model.tonight.size - 4) { rule.runOnIdle { c.navigate(Dir.LEFT) }; rule.waitForIdle() }
            // The picture is drawn in the two colours off the main thread: wait for it.
            val until = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < until) { rule.waitForIdle(); Thread.sleep(50) }
            val dir = File("build/previews").apply { mkdirs() }
            val img = rule.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "tv-home-colours.png").outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            org.junit.Assert.assertTrue(File(dir, "tv-home-colours.png").length() > 5_000)
            // Nothing of the drawing's own red is left in the picture's corner of the screen (Steve's shirt is teal now).
            val px = IntArray(img.width * img.height).also { img.getPixels(it, 0, img.width, 0, 0, img.width, img.height) }
            var reds = 0
            for (y in 0 until img.height * 2 / 5) for (x in 0 until img.width / 4) {
                val p = px[y * img.width + x]; val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                if (r > 190 && g in 50..110 && b in 30..90) reds++
            }
            org.junit.Assert.assertTrue("$reds red pixels left in the picture", reds < 200)
        } finally { rule.runOnIdle { Palette.follow(pieces.styleFor(null, "a"), pieces.styleFor(null, "b")) } ; org.junit.Assert.assertEquals(red, Palette.Red); org.junit.Assert.assertEquals(pink, Palette.Pink) }
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        org.junit.Assert.assertTrue(File(dir, name).length() > 5_000)
    }

    @Test
    fun drawTheInformationPanel() {
        val c = TvController(true, 2f)
        app.couch.alhasapa.diag.Flight.start(ApplicationProvider.getApplicationContext())
        app.couch.alhasapa.diag.Flight.log("player: page loaded (www.nytimes.com)")
        rule.setContent { AlHasapaTheme { TvLayer(c) { Box(Modifier.fillMaxSize().background(Palette.Cream)) } } }
        rule.waitForIdle()
        c.openPanel(TvPanel.INFO)
        save("tv-info-panel.png")
    }

    @Test
    fun drawThePlayersDockFullAndSlim() {
        val c = TvController(true, 2f)
        val pieces = app.couch.alhasapa.data.PiecesConfig.load(ApplicationProvider.getApplicationContext())
        val info = app.couch.alhasapa.player.PlayerInfo(
            done = 2, total = 5, points = 7, nameA = "סטיב", nameB = "רוני",
            pieceA = pieces.styleFor(null, "a"), pieceB = pieces.styleFor(null, "b"), who = null,
        )
        val data = app.couch.alhasapa.player.ChromeData("Wordle", "12:34", info, played = false, showPlayed = true, zoom = 100)
        val actions = app.couch.alhasapa.player.ChromeActions({}, {}, {}, {}, {})
        c.dockExtras = { slim -> PlayerTvDock(slim, c, data, actions) }
        rule.setContent { AlHasapaTheme { TvLayer(c) { Box(Modifier.fillMaxSize().background(Palette.Cream)) } } }
        save("tv-player-dock-full.png")
        c.toggleSlim()
        save("tv-player-dock-slim.png")
    }

    @Test
    fun drawThePairingCardOnTheTv() {
        val c = TvController(true, 2f)
        app.couch.alhasapa.remote.RemoteKeys.ui.value = app.couch.alhasapa.remote.RemoteKeys.Ui(listening = true, code = "4821", codeFor = "Pixel 8", phones = listOf("Pixel 7"))
        rule.setContent { AlHasapaTheme { TvLayer(c) { Box(Modifier.fillMaxSize().background(Palette.Cream)) } } }
        save("tv-pairing.png")
        app.couch.alhasapa.remote.RemoteKeys.ui.value = app.couch.alhasapa.remote.RemoteKeys.Ui()
    }
}
