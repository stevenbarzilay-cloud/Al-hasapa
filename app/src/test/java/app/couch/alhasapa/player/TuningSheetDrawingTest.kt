package app.couch.alhasapa.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.display.DisplayProfile
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The game menu's "ציור" choice: on a TV only, and switching it asks for the other way at once. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], qualifiers = "w960dp-h540dp-land")
class TuningSheetDrawingTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun onATvTheDrawingCanBeSwitchedBothWays() {
        var light by mutableStateOf(false)
        val asked = ArrayList<Boolean>()
        rule.setContent {
            AlHasapaTheme {
                TuningSheet(
                    profile = DisplayProfile(), saved = false, pageSees = null, onChange = {}, onSave = {}, onReset = {}, onClose = {},
                    light = light, onLight = { asked += it; light = it },
                )
            }
        }
        rule.onNodeWithText("ציור").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("קל").performScrollTo().performClick()
        rule.onNodeWithText("קל").performClick()                    // already light: nothing more is asked
        rule.onNodeWithText("מלא").performScrollTo().performClick()
        assertEquals(listOf(true, false), asked)
    }

    @Test
    fun notOnAPhone() {
        rule.setContent {
            AlHasapaTheme {
                TuningSheet(profile = DisplayProfile(), saved = false, pageSees = null, onChange = {}, onSave = {}, onReset = {}, onClose = {})
            }
        }
        rule.onAllNodesWithText("ציור").assertCountEquals(0)
        rule.onAllNodesWithText("מלא").assertCountEquals(0)
    }
}
