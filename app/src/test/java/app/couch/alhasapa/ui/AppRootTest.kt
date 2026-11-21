package app.couch.alhasapa.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.R
import app.couch.alhasapa.hub.HubOrigin
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The startup flow that left a white screen on the phone, run through the real Android framework (Robolectric):
 * the hub must be created and told to load, the splash must lift, and the hub must still be there afterwards.
 * Robolectric has no real Chromium, so these prove the Compose/View side of the flow, not the page rendering.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class AppRootTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun splashDescription() = rule.activity.getString(R.string.splash_description)

    private fun findWebView(): WebView? {
        fun walk(v: View): WebView? = when (v) {
            is WebView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { walk(v.getChildAt(it)) }
            else -> null
        }
        return walk(rule.activity.window.decorView)
    }

    private fun start(playSplash: Boolean = true) {
        rule.mainClock.autoAdvance = false
        rule.setContent { AlHasapaTheme { AppRoot(playSplash = playSplash, reduceMotion = false, onExit = {}) } }
        rule.mainClock.advanceTimeBy(200)
    }

    @Test
    fun hubIsCreatedAndToldToLoadItsPage() {
        start()
        val web = findWebView()
        assertNotNull("no WebView in the view tree", web)
        assertEquals(HubOrigin.url(false), shadowOf(web).lastLoadedUrl)
        val slot = web!!.parent as View // the Compose AndroidView holder; Robolectric's fake WebView never measures itself
        assertTrue("hub slot must have a real size, was ${slot.width}x${slot.height}", slot.width > 0 && slot.height > 0)
    }

    @Test
    fun splashLiftsAfterThePageIsReady_andTheHubStaysMounted() {
        start()
        val web = findWebView()!!
        rule.onNodeWithContentDescription(splashDescription()).assertIsDisplayed()

        shadowOf(web).webViewClient.onPageFinished(web, HubOrigin.url(false))
        rule.mainClock.advanceTimeBy(2_500) // 1.5 s minimum + 0.34 s fade
        rule.onNodeWithContentDescription(splashDescription()).assertDoesNotExist()

        assertTrue("hub WebView was detached", web.isAttachedToWindow)
        val slot = web.parent as View
        assertTrue("hub slot lost its size", slot.width > 0 && slot.height > 0)
        assertEquals("hub WebView must be visible", View.VISIBLE, web.visibility)
        assertTrue("same WebView must survive the splash", findWebView() === web)
    }

    @Test
    fun splashLeavesByItselfEvenIfThePageNeverReportsReady() {
        start()
        rule.mainClock.advanceTimeBy(4_200) // hard cap 3.6 s + fade
        rule.onNodeWithContentDescription(splashDescription()).assertDoesNotExist()
    }

    @Test
    fun noSplashWhenNotPlayed() {
        start(playSplash = false)
        rule.onNodeWithContentDescription(splashDescription()).assertDoesNotExist()
        assertNotNull(findWebView())
    }

    /**
     * Plays the page's part: finishes loading, lets the host's draw check come due (4 s), then answers it.
     * State written from the test thread is published explicitly, because Robolectric's paused main looper would
     * otherwise not deliver it until some unrelated recomposition (on a real device the looper just runs).
     */
    private fun answerHealthCheck(answer: String) {
        val web = findWebView()!!
        shadowOf(web).webViewClient.onPageFinished(web, HubOrigin.url(false))
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(4_500)
        val callback = shadowOf(web).lastEvaluatedJavascriptCallback
        assertNotNull("the host never ran its health check within 4.5 s (last script: ${shadowOf(web).lastEvaluatedJavascript})", callback)
        callback.onReceiveValue(answer)
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(100)
    }

    @Test
    fun aPageThatLoadsButNeverDrawsShowsAVisibleErrorWithTheReason() {
        start(playSplash = false)
        answerHealthCheck("\"TypeError: boom @12:3\"")
        rule.onNodeWithText(rule.activity.getString(R.string.hub_error_title)).assertIsDisplayed()
        rule.onNodeWithText("TypeError: boom @12:3").assertIsDisplayed()
    }

    @Test
    fun aPageThatDrewItselfShowsNoError() {
        start(playSplash = false)
        answerHealthCheck("\"ok\"")
        rule.onNodeWithText(rule.activity.getString(R.string.hub_error_title)).assertDoesNotExist()
    }

    @Test
    fun aHubThatNeverLoadsShowsAVisibleErrorWithRetry_notABlankScreen() {
        start(playSplash = false)
        val first = findWebView()!!
        rule.mainClock.advanceTimeBy(10_500) // load timeout
        rule.onNodeWithText(rule.activity.getString(R.string.hub_error_title)).assertIsDisplayed()

        rule.onNodeWithText(rule.activity.getString(R.string.hub_error_retry)).performClick()
        rule.mainClock.advanceTimeBy(300)
        val second = findWebView()
        assertNotNull("retry must create a fresh WebView", second)
        assertTrue("retry must not reuse the broken WebView", second !== first)
        assertEquals(HubOrigin.url(false), shadowOf(second).lastLoadedUrl)
    }
}
