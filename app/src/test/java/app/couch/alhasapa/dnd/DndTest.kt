package app.couch.alhasapa.dnd

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private class Fake(var casting: Boolean = true, var dnd: Boolean = false, var last: String? = null) : DndSignals {
    override fun isCasting() = casting
    override fun isDndOn() = dnd
    override fun lastShownEvening() = last
    override fun markShown(evening: String) { last = evening }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class DndTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val zone = ZoneId.of("Asia/Jerusalem")
    private fun at(h: Int, day: Int = 21) = ZonedDateTime.of(2026, 11, day, h, 0, 0, 0, zone)

    @Test
    fun anEveningRunsUntilFourInTheMorning() {
        assertEquals("2026-11-21", DndPrompt.eveningKey(at(20)))
        assertEquals("a game after midnight is still tonight", "2026-11-21", DndPrompt.eveningKey(at(2, day = 22)))
        assertEquals("2026-11-22", DndPrompt.eveningKey(at(5, day = 22)))
    }

    @Test
    fun theRuleIsOncePerEveningOnlyWhileCastingAndNotWhenAlreadyQuiet() {
        val now = at(21)
        assertTrue(DndPrompt.shouldShow(Fake(), now))
        assertFalse("not casting", DndPrompt.shouldShow(Fake(casting = false), now))
        assertFalse("already on", DndPrompt.shouldShow(Fake(dnd = true), now))
        assertFalse("already shown tonight", DndPrompt.shouldShow(Fake(last = "2026-11-21"), now))
        assertTrue("shown last night, so tonight again", DndPrompt.shouldShow(Fake(last = "2026-11-20"), now))
        assertTrue("and just after midnight it is still the same evening", !DndPrompt.shouldShow(Fake(last = "2026-11-21"), at(1, day = 22)))
    }

    @Test
    fun theSettingsLinkFallsBackToNearerScreens() {
        val actions = DndPrompt.settingsIntents().map { it.action }
        assertEquals("android.settings.ZEN_MODE_SETTINGS", actions.first())
        assertTrue(actions.size >= 2)
    }

    // One spoken description for the whole chip (TalkBack reads it as one button), so tests look for that.
    private val chipText = "נא לא להפריע. שהתראות לא יקפצו על הטלוויזיה"

    /** A few frames of the paused clock; reading the tree each time makes the test's view of it current. */
    private fun frames(n: Int = 6) = repeat(n) { rule.mainClock.advanceTimeByFrame(); rule.onAllNodes(isRoot()).fetchSemanticsNodes() }

    private fun show(signals: DndSignals) {
        rule.mainClock.autoAdvance = false          // the chip hides itself after 15 s; keep that clock still
        rule.setContent { AlHasapaTheme { DndChipHost(signals = signals) } }
        rule.mainClock.advanceTimeBy(200)
    }

    @Test
    fun theChipAppearsWhileCastingAndOpensTheSettings() {
        val signals = Fake()
        show(signals)
        rule.onNode(hasContentDescription(chipText)).assertIsDisplayed()
        assertNotNull("marked as shown", signals.last)
        rule.onNode(hasContentDescription(chipText)).performClick()
        frames()
        assertEquals("android.settings.ZEN_MODE_SETTINGS", shadowOf(rule.activity).nextStartedActivity.action)
        rule.onNode(hasContentDescription(chipText)).assertDoesNotExist()
    }

    @Test
    fun theCrossDismissesItWithoutOpeningAnything() {
        show(Fake())
        rule.onNode(hasContentDescription("להסתיר")).performClick()
        frames()
        rule.onNode(hasContentDescription(chipText)).assertDoesNotExist()
        assertEquals(null, shadowOf(rule.activity).nextStartedActivity)
    }

    @Test
    fun theChipHidesItselfIfNobodyTouchesIt() {
        show(Fake())
        rule.onNode(hasContentDescription(chipText)).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(16_000)
        rule.onNode(hasContentDescription(chipText)).assertDoesNotExist()
    }

    @Test fun noChipWhenNotCasting() { show(Fake(casting = false)); rule.onNode(hasContentDescription(chipText)).assertDoesNotExist() }
    @Test fun noChipWhenDoNotDisturbIsAlreadyOn() { show(Fake(dnd = true)); rule.onNode(hasContentDescription(chipText)).assertDoesNotExist() }
    @Test fun noChipWhenItAlreadyShowedTonight() { show(Fake(last = DndPrompt.eveningKey())); rule.onNode(hasContentDescription(chipText)).assertDoesNotExist() }
}
