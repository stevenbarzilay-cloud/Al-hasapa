package app.couch.alhasapa.home

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
        rule.setContent {
            AlHasapaTheme { TvHome(m, phoneConnected = true, onPlay = { played += it }, onAccounts = { accounts++ }, onMore = { more++ }) }
        }
        rule.onNodeWithText("המדף של הערב").assertIsDisplayed()
        rule.onNodeWithText("הטלפון מחובר: מקלידים ממנו").assertIsDisplayed()
        rule.onNodeWithText("שיחקנו 1 מתוך ${m.total}", substring = true).assertIsDisplayed()
        rule.onNode(hasContentDescription("Krillion, שיחקנו הערב")).performScrollTo().performClick()
        assertEquals(listOf("krillion"), played)
        rule.onNodeWithText("הגדרות ועוד").performScrollTo().performClick()
        rule.onNodeWithText("החשבונות").performScrollTo().performClick()
        assertEquals(1, more); assertEquals(1, accounts)
    }

    @Test
    fun aColourThatIsNotOneIsTheTint() {
        assertEquals(app.couch.alhasapa.ui.theme.Palette.Tint, HomeModel.colorOf("blue"))
        assertEquals(app.couch.alhasapa.ui.theme.Palette.Tint, HomeModel.colorOf(null))
    }
}
