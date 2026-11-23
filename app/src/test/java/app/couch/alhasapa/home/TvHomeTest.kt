package app.couch.alhasapa.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import java.io.File
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The TV's native home: the shelf with what is left first, the rest after, the evening at the top, and every card a button. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], qualifiers = "w960dp-h540dp-land")
class TvHomeTest {
    @get:Rule val rule = createComposeRule()

    private val catalog = Catalog.parse(File("src/main/assets/games.json").readText())
    private val pieces = PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
    private val date = LocalDate.of(2026, 11, 23)

    private fun stateWithKrillionPlayed(): JSONObject = CouchState.empty().also {
        CouchState.setEntry(it, CouchState.dayKey(date), "krillion", JSONObject().put("w", "").put("t", 1L))
    }

    @Test
    fun theShelfPutsWhatIsLeftFirstAndCountsTheEvening() {
        val m = HomeModel.from(stateWithKrillionPlayed(), catalog, pieces, date)
        val lib = catalog.games.filter { catalog.inLib(it, null) }
        assertEquals(lib.size, m.total)
        assertEquals(1, m.done)
        assertEquals("the played one goes last", "krillion", m.tonight.last().id)
        assertTrue(m.tonight.last().played)
        assertFalse(m.tonight.first().played)
        assertTrue("the rest of the catalogue is a second row", m.more.none { c -> lib.any { it.id == c.id } })
        assertEquals(catalog.games.size, m.tonight.size + m.more.size)
        assertTrue("the day in Hebrew: ${m.day}", m.day.contains("23") && m.day.contains("נובמבר"))
        val krillion = m.tonight.last()
        assertEquals("the site's own colour", HomeModel.colorOf(catalog.siteOf(catalog.game("krillion")!!)!!.color), krillion.color)
    }

    @Test
    fun everyCardIsAButtonAndTheFullHubIsOneStepAway() {
        val m = HomeModel.from(stateWithKrillionPlayed(), catalog, pieces, date)
        val played = ArrayList<String>()
        var more = 0
        var accounts = 0
        var info = 0
        rule.setContent {
            AlHasapaTheme {
                TvHome(m, phoneConnected = true, nav = HomeNav(), onPlay = { played += it }, onAccounts = { accounts++ }, onMore = { more++ }, onInfo = { info++ })
            }
        }
        rule.onNodeWithText("המדף של הערב").assertIsDisplayed()
        rule.onNodeWithText("הטלפון מחובר: מקלידים ממנו").assertIsDisplayed()
        rule.onNodeWithText("שיחקנו 1 מתוך ${m.total}", substring = true).assertIsDisplayed()
        // By touch (the phone's touchpad moves a paw that taps): the cards on the screen and the buttons are plain buttons.
        val first = m.tonight.first()
        rule.onNode(hasContentDescription(first.title)).performClick()
        assertEquals(listOf(first.id), played)
        rule.onNodeWithText("הגדרות ועוד").performClick()
        rule.onNodeWithText("התחברות לאתרים").performClick()
        rule.onNodeWithText("מידע לתיקון תקלות").performClick()
        assertEquals(1, more); assertEquals(1, accounts); assertEquals(1, info)
    }

    @Test
    fun theSecondRowBeginsWithTheSuggestionsWePickedInTheirOrder() {
        val m = HomeModel.from(CouchState.empty(), catalog, pieces, date)
        assertEquals(
            listOf("albumdle", "actorle", "timeguessr", "thrice", "beatlesdaily", "flickle", "cinematrix", "spotle", "chronotrivia", "magweigh"),
            m.more.take(10).map { it.id },
        )
        assertEquals("the permanent games of the list are on the shelf", emptyList<String>(),
            listOf("songless", "framed", "catfishing", "originle", "dozen", "disorderly", "magsize", "bandle") - m.tonight.map { it.id }.toSet())
    }

    @Test
    fun aPhoneLeftOnAnOldVersionIsSaidInWordsAtTheFootOfTheHome() {
        // The TV was updated three times in November 2026 while the phone stayed on 1.9.0 (which crashed on every game), and nothing said so.
        val m = HomeModel.from(CouchState.empty(), catalog, pieces, date)
        var older: String? by androidx.compose.runtime.mutableStateOf("1.9.0")
        var newer: String? by androidx.compose.runtime.mutableStateOf(null)
        rule.setContent {
            AlHasapaTheme {
                TvHome(m, phoneConnected = true, nav = HomeNav(), phoneOlder = older, phoneNewer = newer, onPlay = {}, onAccounts = {}, onMore = {}, onInfo = {})
            }
        }
        rule.onNodeWithText("האפליקציה שבו ישנה (1.9.0)", substring = true).assertIsDisplayed()
        older = ""                                                     // an app from before 1.9.4 does not say which version it is
        rule.onNodeWithText("האפליקציה שבו ישנה מזו שכאן", substring = true).assertIsDisplayed()
        older = null; newer = "1.9.5"
        rule.onNodeWithText("בטלפון מותקנת גרסה חדשה יותר (1.9.5)", substring = true).assertIsDisplayed()
        newer = null
        rule.onNodeWithText("הטלפון מחובר: מקלידים ממנו").assertIsDisplayed()
    }

    @Test
    fun aColourThatIsNotOneIsTheTint() {
        assertEquals(app.couch.alhasapa.ui.theme.Palette.Tint, HomeModel.colorOf("blue"))
        assertEquals(app.couch.alhasapa.ui.theme.Palette.Tint, HomeModel.colorOf(null))
    }
}
