package app.couch.alhasapa.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.BuildConfig
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.data.CouchState
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The isolated player, through the real Android framework. (Robolectric has no Chromium: page rendering and the profile API are checked on the phone.) */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class PlayerActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app get() = context as CouchApp
    private var scenario: ActivityScenario<PlayerActivity>? = null

    @After fun close() { scenario?.close() }

    private fun launch(intent: Intent): ActivityScenario<PlayerActivity> =
        ActivityScenario.launch<PlayerActivity>(intent).also { scenario = it; rule.waitForIdle() }

    private fun webViewOf(activity: PlayerActivity): WebView? {
        fun walk(v: View): WebView? = when (v) {
            is WebView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { walk(v.getChildAt(it)) }
            else -> null
        }
        return walk(activity.window.decorView)
    }

    private fun mainFrameRequest(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    @Test
    fun aGameOpensItsPageSidewaysFullScreenAndKeepsTheScreenOn() {
        val s = launch(PlayerActivity.gameIntent(context, "wordle", null))
        s.onActivity { a ->
            val web = webViewOf(a)
            assertNotNull("no WebView in the player", web)
            assertEquals(app.catalog.game("wordle")!!.u, shadowOf(web).lastLoadedUrl)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, a.requestedOrientation)
            assertTrue("screen must stay on while playing", a.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            assertEquals("match-parent, never wrap-content (the zero layout height trap)", ViewGroup.LayoutParams.MATCH_PARENT, web!!.layoutParams.height)
        }
    }

    @Test
    fun aPersonalLinkIsWhatOpens() {
        app.repo.update { it.put("urls", org.json.JSONObject().put("hzchain", "https://www.haaretz.co.il/riddles/chain-today")) }
        launch(PlayerActivity.gameIntent(context, "hzchain", null)).onActivity { a ->
            assertEquals("https://www.haaretz.co.il/riddles/chain-today", shadowOf(webViewOf(a)).lastLoadedUrl)
        }
    }

    @Test
    fun otherSitesAndOtherAppsAreRefusedWithAToast() {
        launch(PlayerActivity.gameIntent(context, "wordle", null)).onActivity { a ->
            val web = webViewOf(a)!!
            val client = shadowOf(web).webViewClient
            assertTrue(!client.shouldOverrideUrlLoading(web, mainFrameRequest("https://www.nytimes.com/games/connections")))
            assertTrue(client.shouldOverrideUrlLoading(web, mainFrameRequest("https://ads.example.net/x")))
            assertTrue("the person is told what was refused", ShadowToast.getTextOfLatestToast().contains("ads.example.net"))
            assertTrue(client.shouldOverrideUrlLoading(web, mainFrameRequest("market://details?id=com.nytimes.android")))
            assertTrue(client.shouldOverrideUrlLoading(web, mainFrameRequest("nytimes://games")))
        }
    }

    @Test
    fun loggingInMayFollowSingleSignOnButStillNeverAnotherApp() {
        launch(PlayerActivity.loginIntent(context, "nyt", "NYT Games", ProfileNames.GAMES)).onActivity { a ->
            val web = webViewOf(a)!!
            assertEquals("https://myaccount.nytimes.com/auth/login", shadowOf(web).lastLoadedUrl)
            val client = shadowOf(web).webViewClient
            assertTrue(!client.shouldOverrideUrlLoading(web, mainFrameRequest("https://accounts.some-sso.example/authorize")))
            assertTrue(client.shouldOverrideUrlLoading(web, mainFrameRequest("intent://x#Intent;end")))
        }
    }

    @Test
    fun anUnknownGameClosesInsteadOfShowingNothing() {
        val s = ActivityScenario.launch<PlayerActivity>(PlayerActivity.gameIntent(context, "no-such-game", null))
        scenario = s
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, s.state)
    }

    @Test
    fun backWithNoPageHistoryLeavesThePlayer() {
        val s = launch(PlayerActivity.gameIntent(context, "wordle", null))
        s.onActivity { a ->
            assertTrue("a fresh page has nothing to go back to", !webViewOf(a)!!.canGoBack())
            a.onBackPressedDispatcher.onBackPressed()
            assertTrue("back leaves the player", a.isFinishing)
        }
    }

    @Test
    fun weTickedItFromTheStripWritesToTheStateAndTogglesBack() {
        // The TV build has a 72 dp dock on the left, which a 320 dp test screen cannot spare; TvScreensTest covers a real TV screen.
        org.junit.Assume.assumeFalse(BuildConfig.TV)
        val today = CouchState.dayKey(LocalDate.now())
        assertNull(CouchState.entry(app.repo.snapshot(), today, "wordle"))
        launch(PlayerActivity.gameIntent(context, "wordle", null))
        rule.onNodeWithText("שיחקנו").assertIsDisplayed()
        rule.onNodeWithText("שיחקנו").performClick()
        rule.waitForIdle()
        val e = CouchState.entry(app.repo.snapshot(), today, "wordle")
        assertNotNull(e); assertTrue("timestamped for merging", e!!.optLong("t") > 1_000_000_000_000L)
        rule.onNodeWithText("✓ שיחקנו").assertIsDisplayed()
        rule.onNodeWithText("✓ שיחקנו").performClick()
        rule.waitForIdle()
        assertNull(CouchState.entry(app.repo.snapshot(), today, "wordle"))
    }

    @Test
    fun theLoginScreenHasNoWePlayedButton() {
        launch(PlayerActivity.loginIntent(context, "li", "LinkedIn", ProfileNames.RONI))
        rule.onNodeWithText("חזרה").assertIsDisplayed()
        rule.onNodeWithText("שיחקנו").assertDoesNotExist()
    }
}
