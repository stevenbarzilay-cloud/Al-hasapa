package app.couch.alhasapa.results

import app.couch.alhasapa.TestCouch
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.BuildConfig
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.Solo
import app.couch.alhasapa.data.SoloSide
import app.couch.alhasapa.remote.RelayStore
import app.couch.alhasapa.remote.RemoteStore
import app.couch.alhasapa.ui.PLAY_SOLO_TAG
import app.couch.alhasapa.player.PlayerActivity
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The result sheet in the player, the clipboard route, and the Android share target, through the real framework. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class, qualifiers = "w914dp-h411dp-land")
class ResultFlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app get() = context as CouchApp
    private val today get() = CouchState.dayKey(LocalDate.now())
    private var scenario: ActivityScenario<*>? = null

    @org.junit.Before fun ourCouch() { TestCouch.ours() }
    @After fun close() { scenario?.close() }

    private fun player(gameId: String, who: String? = null) {
        scenario = ActivityScenario.launch<PlayerActivity>(PlayerActivity.gameIntent(context, gameId, who)); rule.waitForIdle()
    }

    private fun share(text: String?) {
        val i = Intent(context, ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
        if (text != null) i.putExtra(Intent.EXTRA_TEXT, text)
        scenario = ActivityScenario.launch<ShareReceiverActivity>(i); rule.waitForIdle()
    }

    /** The phone's sheet (fields to paste and type into). On a TV the sheet is buttons and the phone's keys: see PlayerTvDockTest. */
    private fun phoneOnly() = org.junit.Assume.assumeFalse(BuildConfig.TV)

    /** The strip and the panel both have a result button; either one will do. */
    private fun resultButton() = rule.onAllNodesWithText("תוצאה").onFirst().performClick()

    private fun type(label: String, text: String) = rule.onNode(hasSetTextAction() and hasContentDescription(label)).performScrollTo().performTextInput(text)
    private fun press(label: String) = rule.onNodeWithText(label).performScrollTo().performClick()

    // ---- in the player ----

    @Test
    fun theResultButtonOpensTheSheetAndAManualNumberIsSavedAndScored() {
        phoneOnly()
        player("wordle")
        resultButton()
        rule.onNodeWithText("Wordle: התוצאה שלנו").assertIsDisplayed()
        type("או ידנית: ניסיונות", "4")
        rule.onNodeWithText("זוהה: 4/6 · 7 נקודות ספה").assertIsDisplayed()
        press("שמירה"); rule.waitForIdle()
        val e = CouchState.entry(app.repo.snapshot(), today, "wordle")!!
        assertEquals(4, e.getJSONObject("r").getInt("v"))
        assertEquals("נשמר. 7 נקודות ספה.", ShadowToast.getTextOfLatestToast())
        rule.onNodeWithText("Wordle: התוצאה שלנו").assertDoesNotExist()
    }

    @Test
    fun aPastedShareIsUnderstoodBeforeItIsSaved() {
        phoneOnly()
        player("wordle")
        resultButton()
        type("להדביק את מה שהמשחק העתיק", "Wordle 1,618 3/6\n\n🟩🟩🟩🟩🟩")
        rule.onNodeWithText("זוהה: 3/6 · 9 נקודות ספה").assertIsDisplayed()
        assertNull("nothing is saved until Save", CouchState.entry(app.repo.snapshot(), today, "wordle"))
    }

    @Test
    fun whoCrackedItIsCreditAndCanBeChosen() {
        phoneOnly()
        player("wordle")
        resultButton()
        type("או ידנית: ניסיונות", "3")
        rule.onNode(hasContentDescription("רוני") and hasClickAction()).performScrollTo().performClick(); press("שמירה"); rule.waitForIdle()
        assertEquals("b", CouchState.entry(app.repo.snapshot(), today, "wordle")!!.getString("w"))
    }

    @Test
    fun aCopyThatLooksLikeTheResultOpensTheSheetFilledIn() {
        phoneOnly()
        player("wordle")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("result", "Wordle 1,618 3/6\n\n🟩🟩🟩🟩🟩"))
        rule.waitForIdle()
        rule.onNodeWithText("העתקנו את התוצאה מהמשחק. אפשר לתקן לפני השמירה.").assertIsDisplayed()
        rule.onNodeWithText("זוהה: 3/6 · 9 נקודות ספה").assertIsDisplayed()
    }

    @Test
    fun aCopyThatIsNotAResultIsLeftAlone() {
        phoneOnly()
        player("wordle")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", "https://example.org/some/article"))
        rule.waitForIdle()
        rule.onNodeWithText("Wordle: התוצאה שלנו").assertDoesNotExist()
    }

    @Test
    fun aHeadToHeadResultBelongsToWhoIsPlaying() {
        phoneOnly()
        player("queens", who = "b")
        resultButton()
        rule.onNodeWithText("Queens: התוצאה של רוני").assertIsDisplayed()
        type("להדביק את מה שהמשחק העתיק", "Queens #612 | 1:24")
        rule.onNodeWithText("זוהה: 1:24").assertIsDisplayed()
        press("שמירה"); rule.waitForIdle()
        val d = CouchState.entry(app.repo.snapshot(), today, "queens")!!.getJSONObject("d")
        assertEquals(84, d.getJSONObject("b").getInt("v")); assertTrue(!d.has("a"))
        assertEquals("נשמר. מחכים לצד השני.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun theSheetOpensOnWhatIsAlreadySavedAndCanClearIt() {
        phoneOnly()
        app.repo.update { CouchState.setEntry(it, today, "wordle", org.json.JSONObject().put("w", "").put("r", org.json.JSONObject().put("v", 3).put("max", 6).put("kind", "guesses").put("fail", false).put("raw", "Wordle 9 3/6"))) }
        player("wordle")
        resultButton()
        rule.onNodeWithText("Wordle 9 3/6").assertIsDisplayed()
        press("למחוק תוצאה"); rule.waitForIdle()
        val e = CouchState.entry(app.repo.snapshot(), today, "wordle")!!
        assertTrue("the tick stays", e.has("w")); assertTrue(!e.has("r"))
    }

    // ---- the share target ----

    @Test
    fun aSharedWordleIsRecognisedShownAndSavedForTonight() {
        share("Wordle 1,618 3/6\n\n🟩🟩🟩🟩🟩")
        rule.onNodeWithText("Wordle: התוצאה שלנו").assertIsDisplayed()
        rule.onNodeWithText("זוהה: 3/6 · 9 נקודות ספה").assertIsDisplayed()
        press("שמירה"); rule.waitForIdle()
        assertEquals(3, CouchState.entry(app.repo.snapshot(), today, "wordle")!!.getJSONObject("r").getInt("v"))
        assertEquals("נשמר. 9 נקודות ספה.", ShadowToast.getTextOfLatestToast())
        scenario!!.let { sc -> if (sc.state != androidx.lifecycle.Lifecycle.State.DESTROYED) (sc as ActivityScenario<ShareReceiverActivity>).onActivity { assertTrue("it closes after saving", it.isFinishing) } }
    }

    @Test
    fun aSharedLinkedInResultAsksWhoPlayedAndSavesThatSide() {
        share("Queens #612 | 1:24\nFirst placed 👑")
        rule.onNodeWithText("מי שיחק?").assertIsDisplayed()
        rule.onNode(hasContentDescription("משחק: רוני")).performClick()
        rule.onNodeWithText("Queens: התוצאה של רוני").assertIsDisplayed()
        press("שמירה"); rule.waitForIdle()
        assertEquals(84, CouchState.entry(app.repo.snapshot(), today, "queens")!!.getJSONObject("d").getJSONObject("b").getInt("v"))
    }

    @Test
    fun aTextItCannotPlaceAsksWhichGame() {
        share("Some game #5 3 tries")
        rule.onNodeWithText("באיזה משחק מדובר?").assertIsDisplayed()
        rule.onNode(hasContentDescription("Strands")).performScrollTo().performClick()
        rule.onNodeWithText("Strands: התוצאה שלנו").assertIsDisplayed()
    }

    @Test
    fun somethingThatIsNotTextClosesAtOnce() {
        share(null)
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario!!.state)
        assertEquals("אין כאן טקסט של תוצאה.", ShadowToast.getTextOfLatestToast())
        assertNotNull(ShareReceiverActivity.sharedText(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "hi")))
        assertNull("another action is not a share", ShareReceiverActivity.sharedText(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "hi")))
        assertNull("far too long to be a result", ShareReceiverActivity.sharedText(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "x".repeat(20_001))))
    }

    /** 1.11.2: Haaretz's own share of "מי אני?": the game known by its link, the steps read as its hints. */
    @Test
    fun haaretzsShareOfWhoAmIIsUnderstood() {
        phoneOnly()
        share("כל הכבוד! הצלחת לזהות את הדמות תוך 6 צעדים\n\nhttps://www.haaretz.co.il/riddles/2023-02-26/ty-article-static/.premium/00000184-9eae-d199-ade4-dfef359e0000")
        rule.onNodeWithText("מי אני?: התוצאה שלנו").assertIsDisplayed()
        rule.onNodeWithText("זוהה: 6 רמזים · 5 נקודות ספה").assertIsDisplayed()
        press("שמירה"); rule.waitForIdle()
        assertEquals(6, CouchState.entry(app.repo.snapshot(), today, "hzwho")!!.getJSONObject("r").getInt("v"))
        app.repo.update { s -> CouchState.setEntry(s, today, "hzwho", null) }
    }

    // ---- alone (1.11) ----

    /** 2.0: the button that saves a game played alone says where it goes; with no visit on, that is the home couch. */
    private val SAVE_SOLO = "שמירת תוצאת הסולו בספה של סטיב ורוני"


    @Test
    fun aGamePlayedAloneIsSealedUntilTheOtherPlaysItThenTheEnvelopeOpens() {
        phoneOnly()
        app.repo.update { it.remove("solo") }
        scenario = ActivityScenario.launch<PlayerActivity>(PlayerActivity.gameIntent(context, "wordle", "b", solo = true)); rule.waitForIdle()
        resultButton()
        rule.onNodeWithText("⚔️ Wordle · לבד").assertIsDisplayed()
        rule.onNodeWithText("מי פיצח?").assertDoesNotExist()
        type("או ידנית: ניסיונות", "4")
        rule.onNodeWithText("זוהה: 4/6 · ייחתם במעטפה").assertIsDisplayed()
        press(SAVE_SOLO); rule.waitForIdle()
        assertNull("not on the couch's page", CouchState.entry(app.repo.snapshot(), today, "wordle"))
        assertEquals(4.0, Solo.side(app.repo.snapshot(), today, "wordle", "b")!!.v!!, 0.0)
        rule.onNodeWithText("✉️ נחתם במעטפה").assertIsDisplayed()
        rule.onNodeWithText("כשגם סטיב ישחק את זה היום — חשיפה!").assertIsDisplayed()
        // Steve's result comes in (the relay) while the card is up: the envelope opens, line by line, and the crown is Roni's.
        app.repo.update { Solo.put(it, today, "wordle", SoloSide("a", 5.0, 6, false, "guesses", System.currentTimeMillis())) }
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(3_000); rule.waitForIdle()
        rule.onNodeWithText("⚔️ חשיפה!").assertIsDisplayed()
        rule.onNodeWithText("👑 הכתר שלך!").assertIsDisplayed()
        rule.onNode(hasTestTag(SOLO_OK_TAG)).performClick(); rule.waitForIdle()
        rule.onNodeWithText("⚔️ חשיפה!").assertDoesNotExist()
        app.repo.update { it.remove("solo") }
    }

    @Test
    fun aGamePlayedAloneElsewhereHasItsResultTypedIn() {
        phoneOnly()
        app.repo.update { it.remove("solo") }
        RemoteStore(context).me = "a"
        scenario = ActivityScenario.launch<ShareReceiverActivity>(ShareReceiverActivity.soloIntent(context, "wordle")); rule.waitForIdle()
        rule.onNodeWithText("⚔️ Wordle · לבד").assertIsDisplayed()
        type("או ידנית: ניסיונות", "3")
        press(SAVE_SOLO); rule.waitForIdle()
        assertEquals(3.0, Solo.side(app.repo.snapshot(), today, "wordle", "a")!!.v!!, 0.0)
        rule.onNodeWithText("✉️ נחתם במעטפה").assertIsDisplayed()
        RemoteStore(context).me = null
        app.repo.update { it.remove("solo") }
    }

    @Test
    fun aSharedResultAsksTogetherOrAloneOnThePhoneOfSomeoneKnown() {
        phoneOnly()
        app.repo.update { it.remove("solo") }
        RemoteStore(context).me = "b"
        share("Wordle 1,618 3/6\n\n🟩🟩🟩🟩🟩")
        rule.onNodeWithText("ביחד או לבד?").assertIsDisplayed()
        rule.onNode(hasTestTag(PLAY_SOLO_TAG)).performClick(); rule.waitForIdle()
        rule.onNodeWithText("⚔️ Wordle · לבד").assertIsDisplayed()
        press(SAVE_SOLO); rule.waitForIdle()
        assertEquals(3.0, Solo.side(app.repo.snapshot(), today, "wordle", "b")!!.v!!, 0.0)
        assertNull("not on the couch's page", CouchState.entry(app.repo.snapshot(), today, "wordle"))
        assertEquals("solo", RelayStore(context).mode(today, "wordle"))
        RemoteStore(context).me = null
        app.repo.update { it.remove("solo") }
    }

    // ---- a photo of a computer's screen (1.11.1) ----

    @Test
    fun aPhotoOfTheComputersScreenFillsInTheResultOfAGamePlayedElsewhere() {
        phoneOnly()
        app.repo.update { it.remove("solo") }
        RemoteStore(context).me = "a"
        PhotoText.reader = TextReader { _, _, done -> done(PhotoRead.Lines(listOf("Wordle 1,618", "4/6", "Statistics", "27/11/2026"))) }
        try {
            scenario = ActivityScenario.launch<ShareReceiverActivity>(ShareReceiverActivity.soloIntent(context, "wordle")); rule.waitForIdle()
            rule.onNodeWithText("🖼️ תמונה מהגלריה").assertIsDisplayed()
            rule.onNodeWithText("📷 לצלם את המסך").performClick(); rule.waitForIdle()
            // The camera app is asked to write the photo where the app reads it (the app itself never uses the camera).
            scenario!!.onActivity { a ->
                val i = org.robolectric.Shadows.shadowOf(a).nextStartedActivityForResult.intent
                assertEquals(android.provider.MediaStore.ACTION_IMAGE_CAPTURE, i.action)
                @Suppress("DEPRECATION")
                val out = i.getParcelableExtra<android.net.Uri>(android.provider.MediaStore.EXTRA_OUTPUT)!!
                assertEquals("app.couch.alhasapa.photos", out.authority)
            }
            // It was taken: read on the phone, only the line with the result kept.
            scenario!!.onActivity { (it as ShareReceiverActivity).readPhoto(android.net.Uri.parse("content://app.couch.alhasapa.photos/photos/screen.jpg")) }
            rule.waitForIdle()
            rule.onNodeWithText("📷 קראנו מהתמונה. כדאי לבדוק.").performScrollTo().assertIsDisplayed()
            // (2.0: the sheet also says whose couch and which day's puzzle, so on a phone held sideways this line is a scroll away.)
            rule.onNodeWithText("זוהה: 4/6 · ייחתם במעטפה").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("שחקן: סטיב · הספה של סטיב ורוני · החידה של היום").performScrollTo().assertIsDisplayed()
            press(SAVE_SOLO); rule.waitForIdle()
            val side = Solo.side(app.repo.snapshot(), today, "wordle", "a")!!
            assertEquals(4.0, side.v!!, 0.0)
            assertTrue("the photo is gone", !java.io.File(context.cacheDir, "photos/screen.jpg").exists())
        } finally {
            PhotoText.reader = FlavorText
            RemoteStore(context).me = null
            app.repo.update { it.remove("solo") }
        }
    }

    @Test
    fun aPhotoWithNoResultOnItSaysSoAndLeavesTheSheetToBeTyped() {
        phoneOnly()
        RemoteStore(context).me = "b"
        PhotoText.reader = TextReader { _, _, done -> done(PhotoRead.Lines(listOf("Inbox", "Settings"))) }
        try {
            scenario = ActivityScenario.launch<ShareReceiverActivity>(ShareReceiverActivity.soloIntent(context, "wordle")); rule.waitForIdle()
            scenario!!.onActivity { (it as ShareReceiverActivity).readPhoto(android.net.Uri.parse("content://app.couch.alhasapa.photos/photos/screen.jpg")) }
            rule.waitForIdle()
            rule.onNodeWithText("📷 לא מצאנו תוצאה בתמונה. אפשר להקליד אותה.").assertIsDisplayed()
            type("או ידנית: ניסיונות", "5")
            rule.onNodeWithText("זוהה: 5/6 · ייחתם במעטפה").assertIsDisplayed()
        } finally {
            PhotoText.reader = FlavorText
            RemoteStore(context).me = null
        }
    }
}
