package app.couch.alhasapa.home

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The TV's home under the remote, laid out for real on a TV-sized screen: the arrows reach every card (1.9.0's did not get past the
 * fifth), the card in focus is always on the screen, OK opens it, and the information panel does not let the arrows wander behind it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], qualifiers = "w960dp-h540dp-television-xhdpi")
class TvHomeRemoteTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val catalog = Catalog.parse(File("src/main/assets/games.json").readText())
    private val pieces = PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
    private val date = LocalDate.of(2026, 11, 23)
    private val c = TvController(true, 2f)
    private val nav = HomeNav()
    private val played = ArrayList<String>()
    private val pressed = ArrayList<String>()
    private var state by mutableStateOf(CouchState.empty())
    private var shown by mutableStateOf(true)

    private fun model() = HomeModel.from(state, catalog, pieces, date)

    private fun show() {
        rule.setContent {
            AlHasapaTheme {
                TvLayer(c) {
                    if (shown) {
                        TvHome(
                            model(), phoneConnected = false, nav = nav,
                            onPlay = { played += it }, onAccounts = { pressed += "accounts" }, onMore = { pressed += "more" },
                            onInfo = { c.openPanel(TvPanel.INFO) },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(code: Int) {
        rule.runOnIdle {
            assertTrue("the remote's key is taken", c.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code)))
            c.handleKey(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        rule.waitForIdle()
    }

    private fun left() = press(KeyEvent.KEYCODE_DPAD_LEFT)
    private fun right() = press(KeyEvent.KEYCODE_DPAD_RIGHT)
    private fun up() = press(KeyEvent.KEYCODE_DPAD_UP)
    private fun down() = press(KeyEvent.KEYCODE_DPAD_DOWN)
    private fun ok() = press(KeyEvent.KEYCODE_DPAD_CENTER)

    private fun focusedGame(): String? = c.focusedId?.removePrefix(CARD_PREFIX)
    private fun cardOf(id: String) = (model().tonight + model().more).first { it.id == id }

    /** The card in focus is on the screen, the whole of it (not cut by an edge, not scrolled half away). */
    private fun assertFocusedCardIsShown() {
        val card = cardOf(focusedGame()!!)
        val node = rule.onNode(hasContentDescription(card.title + ", " + card.kind + if (card.played) ", שיחקנו הערב" else "")).assertIsDisplayed().fetchSemanticsNode()
        val seen = node.boundsInWindow                       // what is left of it after every clip
        assertTrue("${card.id}: all of its width shows ($seen of ${node.size})", seen.width >= node.size.width - 1f)
        assertTrue("${card.id}: all of its height shows ($seen of ${node.size})", seen.height >= node.size.height - 1f)
        assertTrue("${card.id}: inside the screen ($seen)", seen.left >= 0f && seen.right <= 1920f && seen.top >= 0f && seen.bottom <= 1080f)
    }

    @Test
    fun theHomeOpensOnTheFirstGameLeftWithNoDockAndEverythingOnOneScreen() {
        show()
        assertEquals(model().tonight.first().id, focusedGame())
        rule.onNode(hasContentDescription("להפעיל כפה (עכבר)")).assertDoesNotExist()
        rule.onNodeWithText("המדף של הערב").assertIsDisplayed()
        rule.onNodeWithText("עוד משחקים").assertIsDisplayed()
        rule.onNodeWithText("התחברות לאתרים").assertIsDisplayed()
        rule.onNodeWithText("הגדרות ועוד").assertIsDisplayed()
        rule.onNodeWithText("מידע לתיקון תקלות").assertIsDisplayed()
        // Nothing needs scrolling down for: the buttons at the foot end above the bottom of a TV screen, with a margin for TVs that crop.
        val foot = rule.onNodeWithText("התחברות לאתרים").fetchSemanticsNode().boundsInWindow
        assertTrue("the foot of the home is on the screen with a margin: $foot", foot.bottom <= 1080f - 20f)
    }

    @Test
    fun leftWalksTheWholeShelfCardByCardAndRightComesAllTheWayBack() {
        show()
        val shelf = model().tonight.map { it.id }
        assertTrue("a shelf much wider than the screen: ${shelf.size}", shelf.size > 10)
        for (i in 1 until shelf.size) {
            left()
            assertEquals("Left number $i", shelf[i], focusedGame())
            assertFocusedCardIsShown()
        }
        left()
        assertEquals("at the row's end the focus stays", shelf.last(), focusedGame())
        for (i in shelf.size - 2 downTo 0) {
            right()
            assertEquals(shelf[i], focusedGame())
            assertFocusedCardIsShown()
        }
        right()
        assertEquals(shelf.first(), focusedGame())
    }

    @Test
    fun krillionIsReachedAndOkOpensIt() {
        show()
        val shelf = model().tonight.map { it.id }
        val at = shelf.indexOf("krillion")
        assertTrue("on the shelf, beyond the first screenful", at > 5)
        repeat(at) { left() }
        assertEquals("krillion", focusedGame())
        // What it is, at the top of the home: its name, its kind and site, and the sentence about it.
        rule.onNodeWithTag(DETAIL_TITLE).assertIsDisplayed().assertTextEquals("Krillion")
        rule.onNodeWithTag(DETAIL_KICKER).assertTextEquals("ידע · " + cardOf("krillion").siteName)
        rule.onNodeWithTag(DETAIL_SUMMARY).assertIsDisplayed().assertTextEquals(cardOf("krillion").description)
        ok()
        assertEquals(listOf("krillion"), played)
    }

    @Test
    fun downGoesToTheOtherGamesThenTheButtonsAndEveryRowKeepsItsPlace() {
        show()
        val shelf = model().tonight.map { it.id }
        val more = model().more.map { it.id }
        left(); left()
        down()
        assertEquals(more[0], focusedGame()); assertFocusedCardIsShown()
        repeat(7) { left() }
        assertEquals(more[7], focusedGame()); assertFocusedCardIsShown()
        down()
        assertEquals(ACCOUNTS_ID, c.focusedId)
        left()
        assertEquals(MORE_ID, c.focusedId)
        ok()
        assertEquals(listOf("more"), pressed)
        right(); ok()
        assertEquals(listOf("more", "accounts"), pressed)
        down()
        assertEquals("nothing under the buttons", ACCOUNTS_ID, c.focusedId)
        up()
        assertEquals("back where that row was left", more[7], focusedGame()); assertFocusedCardIsShown()
        up()
        assertEquals(shelf[2], focusedGame()); assertFocusedCardIsShown()
        up()
        assertEquals("nothing above the shelf", shelf[2], focusedGame())
    }

    @Test
    fun theInformationPanelOpensFromItsButtonAndTheArrowsDoNotWanderBehindIt() {
        show()
        down(); down(); left(); left()
        assertEquals(INFO_ID, c.focusedId)
        ok()
        assertEquals(TvPanel.INFO, c.panel)
        rule.onNodeWithText("אם משהו לא עובד", substring = true).assertIsDisplayed()
        left(); right()
        assertEquals("the focus stays on the panel's own button", TvController.PANEL_CLOSE_ID, c.focusedId)
        ok()
        assertNull("OK closed it", c.panel)
        assertEquals("and the focus is back on the button that opened it", INFO_ID, c.focusedId)
        assertTrue(played.isEmpty())
    }

    @Test
    fun afterAGameWasPlayedTheFocusIsOnTheNextOneLeft() {
        show()
        val first = model().tonight.first().id
        val second = model().tonight[1].id
        ok()
        assertEquals(listOf(first), played)
        // The game was played (the player marks it); back on the home the shelf has moved it after the ones still left.
        rule.runOnIdle {
            state = CouchState.copy(state).also { CouchState.setEntry(it, CouchState.dayKey(date), first, JSONObject().put("w", "").put("t", 1L)) }
        }
        rule.waitForIdle()
        assertEquals(second, model().tonight.first().id)
        assertEquals(second, focusedGame())
        assertFocusedCardIsShown()
        rule.onNodeWithText("שיחקנו 1 מתוך ${model().total}", substring = true).assertIsDisplayed()
    }

    @Test
    fun leavingTheHomeGivesTheDockBackAndComingHomeFindsThePlace() {
        show()
        left(); left(); left()
        val at = focusedGame()
        rule.runOnIdle { c.pageFirst(); shown = false }                 // as "הגדרות ועוד" does
        rule.waitForIdle()
        rule.onNode(hasContentDescription("להפעיל כפה (עכבר)")).assertIsDisplayed()
        assertNull(c.screenNav)
        rule.runOnIdle { shown = true }
        rule.waitForIdle()
        assertEquals(at, focusedGame())
        assertFocusedCardIsShown()
        rule.onNode(hasContentDescription("להפעיל כפה (עכבר)")).assertDoesNotExist()
    }
}
