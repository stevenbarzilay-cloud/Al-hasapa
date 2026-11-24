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
        repeat(model.tonight.size - 4) { rule.runOnIdle { c.navigate(Dir.LEFT) }; rule.waitForIdle() }
        val dir = File("build/previews").apply { mkdirs() }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "tv-home-colours.png").outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        org.junit.Assert.assertTrue(File(dir, "tv-home-colours.png").length() > 5_000)
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
