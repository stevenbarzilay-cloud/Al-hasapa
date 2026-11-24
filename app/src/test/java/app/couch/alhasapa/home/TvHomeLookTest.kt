package app.couch.alhasapa.home

import android.content.Context
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.tv.TvController
import app.couch.alhasapa.tv.TvLayer
import app.couch.alhasapa.tv.TvPanel
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * How the TV's home is laid out (1.9.5), measured on a TV-sized screen: the logo at the top right, the two of us at the top left with
 * the picture under the names from the same margin, the cushions straight in a row on a common line at even gaps (in focus too), and
 * everything the home had before still on it. And what the brief asked to be tried with the remote on the new look: quick presses,
 * a shorter shelf, the buttons at the foot, one of us changing colour.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvHomeLookTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val catalog = Catalog.parse(File("src/main/assets/games.json").readText())
    private val pieces = PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
    private val date = LocalDate.of(2026, 11, 23)
    private val c = TvController(true, 2f)
    private val nav = HomeNav()
    private val played = ArrayList<String>()
    private var state by mutableStateOf(CouchState.empty())
    private var older by mutableStateOf<String?>(null)

    private fun model() = HomeModel.from(state, catalog, pieces, date)

    private fun show() {
        warmHomeArt(context)                                  // as the app does while it starts
        rule.setContent {
            AlHasapaTheme {
                TvLayer(c) {
                    TvHome(model(), phoneConnected = older != null, nav = nav, phoneOlder = older, onPlay = { played += it }, onAccounts = {}, onMore = {}, onInfo = { c.openPanel(TvPanel.INFO) })
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(code: Int) {
        rule.runOnIdle { c.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code)); c.handleKey(KeyEvent(KeyEvent.ACTION_UP, code)) }
        rule.waitForIdle()
    }
    private fun left() = press(KeyEvent.KEYCODE_DPAD_LEFT)
    private fun right() = press(KeyEvent.KEYCODE_DPAD_RIGHT)
    private fun up() = press(KeyEvent.KEYCODE_DPAD_UP)
    private fun down() = press(KeyEvent.KEYCODE_DPAD_DOWN)
    private fun focusedGame(): String? = c.focusedId?.removePrefix(CARD_PREFIX)

    /** Where a game's cushion is on the screen (the whole button), or null when it is not there. */
    private fun cushion(card: HomeCard): Rect? =
        rule.onAllNodes(hasContentDescription(spoken(card))).fetchSemanticsNodes().firstOrNull()?.boundsInWindow?.takeIf { it.width > 0f }

    /** What a screen reader is told about a cushion: the whole name, the kind of game, and that it was played. */
    private fun spoken(card: HomeCard) = card.title + ", " + card.kind + if (card.played) ", שיחקנו הערב" else ""
    private fun whole(r: Rect) = r.left >= 0f && r.right <= 1920f && abs(r.width - WIDTH) < 1f

    @Test
    fun theLogoIsAtTheTopRightAndThePictureUnderTheNamesFromTheSameMargin() {
        show()
        val logo = rule.onNode(hasContentDescription("על הספה שלנו")).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val picture = rule.onNode(hasContentDescription("משחקים יחד על הספה", substring = true)).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val names = listOf("סטיב", "רוני").map { rule.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInWindow }
        val margin = 96f                                       // 48 dp at each side
        assertEquals("the logo's right edge is the margin", 1920f - margin, logo.right, 1f)
        assertTrue("at the top: $logo", logo.top < 60f && logo.bottom < 130f)
        // The originals, in their own proportions (the drawing itself, without the empty margin of its file).
        assertEquals(872f / 379f, logo.width / logo.height, 0.03f)
        assertEquals(1096f / 703f, picture.width / picture.height, 0.03f)
        val leftName = names.minOf { it.left }
        assertEquals("the names begin at the left margin", margin, leftName, 1f)
        assertEquals("and so does the picture", leftName, picture.left, 1f)
        assertTrue("the picture is under the names: $picture, ${names.maxOf { it.bottom }}", picture.top >= names.maxOf { it.bottom })
        assertTrue("the names are at the left, the logo at the right", names.maxOf { it.right } < 960f && logo.left > 960f)
        // Both pictures really are the app's own files, read small.
        for (p in HomeArt.Picture.entries) {
            val art = HomeArt.cached(p)
            assertNotNull(p.name, art)
            assertEquals(p.name, p.aspect, art!!.aspect, 0.02f)
            assertTrue("${p.name} was read no larger than the screen needs: ${art.image.height} px tall", art.image.height < 2 * 2 * 110 + 200)
        }
    }

    @Test
    fun theCushionsStandStraightOnOneLineTheSameSizeAtEvenGapsAlsoInFocus() {
        show()
        val shelf = model().tonight
        // At least five whole cushions on a TV, and the next one peeking in to say there is more.
        fun shown() = shelf.mapNotNull { card -> cushion(card)?.let { card to it } }
        val first = shown()
        val wholes = first.filter { whole(it.second) }
        assertTrue("five whole cushions: ${wholes.size}", wholes.size >= 5)
        assertTrue("and part of another", first.size > wholes.size)
        fun straight(rows: List<Pair<HomeCard, Rect>>) {
            val r = rows.map { it.second }
            assertTrue("one size: ${r.map { it.size }}", r.all { abs(it.width - r[0].width) < 1f && abs(it.height - r[0].height) < 1f })
            assertTrue("one line: ${r.map { it.top }}", r.all { abs(it.top - r[0].top) < 1f })
            val gaps = r.zipWithNext { a, b -> a.left - b.right }             // right to left: the next one is to the left
            assertTrue("even gaps: $gaps", gaps.all { abs(it - gaps[0]) < 1f } && gaps[0] > 0f)
        }
        straight(wholes)
        assertEquals("the demo's cushion: 232 to 150", 232f / 150f, (wholes[0].second.width - 20f) / (wholes[0].second.height - 20f), 0.01f)
        // The one in focus is the first, and it is exactly like the others; so is the next one when the focus moves on.
        assertEquals(shelf[0].id, focusedGame())
        left(); left()
        assertEquals(shelf[2].id, focusedGame())
        straight(shown().filter { whole(it.second) })
        // The second shelf is the same, under the first.
        val more = model().more.mapNotNull { card -> cushion(card)?.let { card to it } }.filter { whole(it.second) }
        assertTrue(more.size >= 5); straight(more)
        assertTrue(more[0].second.top > wholes[0].second.bottom)
        assertEquals("the same cushions on both shelves", wholes[0].second.size, more[0].second.size)
    }

    @Test
    fun theTopOfTheHomeSaysWhatIsInFocusAGameOrOneOfTheButtons() {
        show()
        val first = model().tonight.first()
        rule.onNodeWithTag(DETAIL_TITLE).assertIsDisplayed().assertTextEquals(first.title)
        rule.onNodeWithTag(DETAIL_KICKER).assertTextEquals(first.kind + " · " + first.siteName)
        rule.onNodeWithTag(DETAIL_SUMMARY).assertTextEquals(first.description)
        // A long name is whole here, and in what a screen reader is told, even where the cushion has no room for all of it.
        val long = model().tonight.first { it.title.length > 20 }
        repeat(model().tonight.indexOf(long)) { left() }
        rule.onNodeWithTag(DETAIL_TITLE).assertTextEquals(long.title)
        assertEquals(
            "the whole name and the kind of game, in words", long.title + ", " + long.kind,
            rule.onNode(hasContentDescription(spoken(long))).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single(),
        )
        // The buttons at the foot say what they are for.
        down(); down()
        assertEquals(ACCOUNTS_ID, c.focusedId)
        rule.onNodeWithTag(DETAIL_TITLE).assertTextEquals("התחברות לאתרים")
        rule.onNodeWithTag(DETAIL_SUMMARY).assertIsDisplayed()
        left()
        rule.onNodeWithTag(DETAIL_TITLE).assertTextEquals("הגדרות ועוד")
        left()
        rule.onNodeWithTag(DETAIL_TITLE).assertTextEquals("מידע לתיקון תקלות")
        // With the information panel over the home the focus is on the panel's own button; the home says what it said.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(TvPanel.INFO, c.panel)
        rule.onNodeWithTag(DETAIL_TITLE).assertTextEquals("מידע לתיקון תקלות")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // A game played this evening says so there too.
        up(); up()
        rule.runOnIdle { state = CouchState.copy(state).also { CouchState.setEntry(it, CouchState.dayKey(date), long.id, JSONObject().put("w", "").put("t", 1L)) } }
        rule.waitForIdle()
        repeat(model().tonight.size) { left() }
        assertEquals(long.id, focusedGame())
        rule.onNodeWithTag(DETAIL_KICKER).assertTextEquals(long.kind + " · " + long.siteName + " · שיחקנו הערב")
    }

    @Test
    fun everythingTheHomeHadIsStillOnItAndOnOneScreen() {
        older = "1.9.0"
        show()
        val m = model()
        for (text in listOf("המדף של הערב", "עוד משחקים", "התחברות לאתרים", "הגדרות ועוד", "מידע לתיקון תקלות")) rule.onNodeWithText(text).assertIsDisplayed()
        rule.onNodeWithText("שיחקנו 0 מתוך ${m.total}", substring = true).assertIsDisplayed()      // the evening's count
        rule.onNodeWithText("נקודות ספה", substring = true).assertIsDisplayed()                     // the score
        rule.onNodeWithText("נובמבר", substring = true).assertIsDisplayed()                         // the day
        rule.onNodeWithText("האפליקציה שבו ישנה (1.9.0)", substring = true).assertIsDisplayed()     // the line about the phone
        rule.runOnIdle { older = null }
        rule.onNodeWithText("להקליד מהטלפון", substring = true).assertIsDisplayed()                 // how to type from the phone
        // Nothing needs scrolling down for: the foot ends above the bottom of the screen with a margin for TVs that crop the picture.
        val foot = rule.onNodeWithText("התחברות לאתרים").fetchSemanticsNode().boundsInWindow
        assertTrue("$foot", foot.bottom <= 1080f - 40f)
    }

    @Test
    fun quickPressesAShorterShelfAndTheButtonsAtTheFoot() {
        // All but three games on the shelf: the second shelf is short.
        val keep = setOf("letterboxed", "tiles", "worldle")
        state = CouchState.empty().also { s -> s.put("prefs", JSONObject().apply { catalog.games.filter { !it.lib && it.id !in keep }.forEach { put(it.id, "yes") } }) }
        show()
        val shelf = model().tonight.map { it.id }
        val more = model().more.map { it.id }
        assertEquals(3, more.size); assertTrue(shelf.size > 60)
        // Twelve presses one after the other, with no time for the screen to settle between them.
        rule.runOnIdle { repeat(12) { c.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)); c.handleKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)) } }
        rule.waitForIdle()
        assertEquals("every press counted", shelf[12], focusedGame())
        fun shownWhole(id: String) = cushion((model().tonight + model().more).first { it.id == id })?.let(::whole) == true
        assertTrue("and the cushion in focus is on the screen, whole", shownWhole(shelf[12]))
        rule.onNodeWithTag(DETAIL_TITLE).assertTextEquals(model().tonight[12].title)
        // Down to the short shelf: its first game (there is no thirteenth there); to its end and no further; then the buttons.
        down()
        assertEquals(more[0], focusedGame()); assertTrue(shownWhole(more[0]))
        left(); left(); left(); left()
        assertEquals("the short shelf ends at its third", more[2], focusedGame()); assertTrue(shownWhole(more[2]))
        down()
        assertEquals(ACCOUNTS_ID, c.focusedId)
        left(); left(); left()
        assertEquals("the last button", INFO_ID, c.focusedId)
        up()
        assertEquals("back where the short shelf was left", more[2], focusedGame())
        up()
        assertEquals("and the long one, where it was left", shelf[12], focusedGame()); assertTrue(shownWhole(shelf[12]))
        // And back the other way, quickly.
        rule.runOnIdle { repeat(12) { c.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)); c.handleKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)) } }
        rule.waitForIdle()
        assertEquals(shelf[0], focusedGame()); assertTrue(shownWhole(shelf[0]))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(shelf[0]), played)
    }

    @Test
    fun holdingAnArrowWalksAlongTheShelfAtASteadyPace() {
        show()
        val shelf = model().tonight
        fun held(repeat: Int) = c.handleKey(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, repeat))
        // A remote repeats a held key many times a second. However many repeats arrive in one instant, the home takes one step...
        rule.runOnIdle { held(0); repeat(6) { held(it + 1) } }
        rule.waitForIdle()
        assertEquals(shelf[1].id, focusedGame())
        // ...and the next one when its time has come: cushion after cushion, each whole on the screen, past the first screenful.
        for (i in 2..10) {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(TvController.REPEAT_MS + 5))
            rule.runOnIdle { held(20 + i); held(40 + i); held(60 + i) }
            rule.waitForIdle()
            assertEquals("step $i", shelf[i].id, focusedGame())
            assertTrue("step $i", cushion(shelf[i])?.let(::whole) == true)
        }
        rule.runOnIdle { c.handleKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)) }
        rule.onNodeWithTag(DETAIL_TITLE).assertTextEquals(shelf[10].title)
    }

    @Test
    fun whenOneOfUsChangesColourTheCushionsChangeAndTheFocusStaysWhereItWas() {
        show()
        repeat(7) { left() }
        val at = focusedGame()!!
        val before = model().tonight.associate { it.id to it.look.base }
        rule.runOnIdle { state = CouchState.copy(state).also { it.put("pieces", JSONObject().put("b", JSONObject().put("shape", "triangle").put("color", "green"))) } }
        rule.waitForIdle()
        val after = model().tonight.associate { it.id to it.look.base }
        assertNotEquals(before, after)
        assertEquals(at, focusedGame())
        assertTrue(cushion(model().tonight.first { it.id == at })?.let(::whole) == true)
        rule.onNodeWithTag(DETAIL_TITLE).assertTextEquals(model().tonight.first { it.id == at }.title)
        left()
        assertEquals(model().tonight[8].id, focusedGame())
    }

    private companion object {
        /** A cushion's button on a TV, in pixels: the cushion (90 dp tall, 232 to 150) and the room for its threads around it. */
        const val WIDTH = (90f * 232f / 150f + 10f) * 2f
    }
}
