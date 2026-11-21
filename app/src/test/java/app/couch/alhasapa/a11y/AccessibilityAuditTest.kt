package app.couch.alhasapa.a11y

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.accounts.AccountsActivity
import app.couch.alhasapa.player.PlayerActivity
import app.couch.alhasapa.results.ShareReceiverActivity
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A TalkBack and touch audit of every native screen: each thing that can be tapped must have a spoken name and be at least
 * 48 x 48 dp, and the panels read from the couch must use 20 sp or more. (The hub is a web page with its own aria labels.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class, qualifiers = "w914dp-h411dp-land")
class AccessibilityAuditTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<*>? = null

    @After fun close() { scenario?.close() }

    private fun audit(where: String) {
        val density = context.resources.displayMetrics.density
        val taps: SemanticsNodeInteractionCollection = rule.onAllNodes(hasClickAction())
        val n = taps.fetchSemanticsNodes().size
        assertTrue("$where: nothing tappable found, the audit would prove nothing", n > 0)
        val problems = mutableListOf<String>()
        for (i in 0 until n) {
            val node = taps[i].fetchSemanticsNode()
            val cfg = node.config
            val name = cfg.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.takeIf { it.isNotBlank() }
                ?: cfg.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }
            // The layout size, not the on-screen bounds: those are cut off by scrolling and by the screen edge.
            val wDp = node.size.width / density
            val hDp = node.size.height / density
            if (name == null) problems += "no spoken name at ${node.boundsInRoot}"
            else if (wDp < 47.5f || hDp < 47.5f) problems += "'$name' is ${wDp.toInt()}x${hDp.toInt()} dp"
        }
        assertTrue("$where: " + problems.joinToString("; "), problems.isEmpty())
    }

    @Test
    fun thePlayerStripPanelsAndSheets() {
        scenario = ActivityScenario.launch<PlayerActivity>(PlayerActivity.gameIntent(context, "wordle", null)); rule.waitForIdle()
        audit("player strip and panels")
        rule.onNode(hasContentDescription("תפריט")).performClick(); rule.waitForIdle()
        audit("tuning sheet")
        rule.onNodeWithText("סגירה").performClick(); rule.waitForIdle()
        rule.onAllNodesWithText("תוצאה").onFirst().performClick(); rule.waitForIdle()
        audit("result sheet (together)")
    }

    @Test
    fun theMagnifiedViewWithItsArrows() {
        (context as CouchApp).repo.update { it.put("displays", org.json.JSONObject().put("nytimes.com/games/wordle", org.json.JSONObject().put("zoom", 220))) }
        scenario = ActivityScenario.launch<PlayerActivity>(PlayerActivity.gameIntent(context, "wordle", null)); rule.waitForIdle()
        rule.onNode(hasContentDescription("תפריט")).performClick(); rule.waitForIdle()
        audit("tuning sheet with the pan arrows")
    }

    private fun launchWith(gameId: String, key: String, profile: org.json.JSONObject) {
        (context as CouchApp).repo.update { it.put("displays", org.json.JSONObject().put(key, profile)) }
        scenario = ActivityScenario.launch<PlayerActivity>(PlayerActivity.gameIntent(context, gameId, null)); rule.waitForIdle()
    }

    @Test
    fun everyPlacementAndSizeOfTheAlwaysVisibleUi() {
        val key = "nytimes.com/games/wordle"
        for ((name, p) in listOf(
            "top bar, small" to org.json.JSONObject().put("ui", "top").put("uiSize", "s"),
            "bottom bar, large" to org.json.JSONObject().put("ui", "bottom").put("uiSize", "l"),
            "top and bottom, medium" to org.json.JSONObject().put("ui", "both").put("uiSize", "m"),
            "just the menu button" to org.json.JSONObject().put("ui", "minimal"),
            "wide mode with its bar" to org.json.JSONObject().put("mode", "wide"),
            "panels, small (compact)" to org.json.JSONObject().put("ui", "sides").put("uiSize", "s"),
            "panels, large" to org.json.JSONObject().put("ui", "sides").put("uiSize", "l"),
        )) {
            scenario?.close()
            launchWith("wordle", key, p)
            audit(name)
        }
    }

    @Test
    fun theHeadToHeadSheet() {
        scenario = ActivityScenario.launch<PlayerActivity>(PlayerActivity.gameIntent(context, "queens", "a")); rule.waitForIdle()
        rule.onAllNodesWithText("תוצאה").onFirst().performClick(); rule.waitForIdle()
        audit("result sheet (head to head)")
    }

    @Test
    fun theShareTarget() {
        val i = Intent(context, ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "who knows #5")
        scenario = ActivityScenario.launch<ShareReceiverActivity>(i); rule.waitForIdle()
        audit("share target: which game")
    }

    @Test
    fun theAccountsScreen() {
        scenario = ActivityScenario.launch<AccountsActivity>(Intent(context, AccountsActivity::class.java)); rule.waitForIdle()
        audit("accounts")
    }

    @Test
    fun textInThePlayerPanelsIsTwentySpOrMore() {
        // The brief: at least 20 sp in the panels read from the couch. Everything in them is set in PlayerPanels.kt.
        val src = File("src/main/java/app/couch/alhasapa/player/PlayerPanels.kt").readText()
        val sizes = src.lines().filter { it.contains("fontSize") }.flatMap { l -> Regex("(\\d+)\\.sp").findAll(l).map { it.groupValues[1].toInt() }.toList() }
        assertTrue("found the font sizes", sizes.size > 8)
        assertTrue("every size in the panels is 20 sp or more: $sizes", sizes.all { it >= 20 })
        assertTrue("every fontSize is a literal of 20+ or PanelText", src.lines().filter { it.contains("fontSize") }.none { Regex("fontSize = (?!PanelText|if|\\d)").containsMatchIn(it) })
        assertTrue("PanelText is 20.sp", Regex("PanelText = 20\\.sp").containsMatchIn(src))
    }
}
