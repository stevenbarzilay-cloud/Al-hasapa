package app.couch.alhasapa.couches

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.BuildConfig
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.MainActivity
import app.couch.alhasapa.R
import app.couch.alhasapa.TestCouch
import app.couch.alhasapa.data.CouchRef
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.data.Person
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.data.SoloSide
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.data.StateStore
import app.couch.alhasapa.diag.CrashReport
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.remote.CouchStore
import app.couch.alhasapa.remote.GuestEntry
import app.couch.alhasapa.remote.KeyClient
import app.couch.alhasapa.remote.KeyLink
import app.couch.alhasapa.tv.TvMode
import app.couch.alhasapa.remote.KeyServer
import app.couch.alhasapa.remote.Links
import app.couch.alhasapa.remote.RemoteStore
import app.couch.alhasapa.remote.RepoCoupleBook
import app.couch.alhasapa.remote.SavedCouch
import app.couch.alhasapa.remote.SoloTarget
import app.couch.alhasapa.remote.Visit
import app.couch.alhasapa.results.SoloCard
import app.couch.alhasapa.results.SoloLine
import app.couch.alhasapa.results.SoloOutcome
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import app.couch.alhasapa.ui.theme.Palette
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the screens of couches say (2.0), upright as on a phone: in words and with a mark of its own each, never by colour alone. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w411dp-h891dp-port-xxhdpi")
class CouchesScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val pieces by lazy { PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText()) }
    private val home = CouchRef(TestCouch.ID, TestCouch.NAME)
    private val danny = CouchRef("c1111111111111aa", "הספה של דני")
    private val pressed = ArrayList<String>()

    private val actions = object : CouchesActions {
        override fun back() { pressed += "back" }
        override fun rename() { pressed += "rename" }
        override fun endVisit() { pressed += "end" }
        override fun keyboard() { pressed += "keyboard" }
        override fun sendPending() { pressed += "send" }
        override fun startVisit(c: SavedCouch) { pressed += "visit:${c.id}" }
        override fun setHome(c: SavedCouch) { pressed += "home:${c.id}" }
        override fun remove(c: SavedCouch) { pressed += "remove:${c.id}" }
        override fun pick(n: Nearby) { pressed += "pick:${n.tv.id}" }
        override fun scan() { pressed += "scan" }
        override fun typeAddress() { pressed += "address" }
        override fun shareApp() { pressed += "share" }
        override fun checkLink() { pressed += "check" }
    }

    private fun save(name: String, dialog: Boolean = false) {
        rule.waitForIdle()
        val dir = File("build/previews").apply { mkdirs() }
        val img = (if (dialog) rule.onNode(isDialog()) else rule.onRoot()).captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theListSaysWhichCouchIsTheHomeWhichAreSavedWhereTheVisitIsAndWhatIsConnectedNow() {
        val visit = Visit(danny.id, danny.name, "tv9", "u1234567", "סטיב", "2026-11-27", 1)
        val pending = GuestEntry(danny.id, danny.name, "u1234567", "סטיב", "2026-11-27", "strands", SoloSide("u1234567", 4.0, t = 5), sent = false)
        val view = CouchesView(
            home = home, homeTvs = 1,
            saved = listOf(SavedCouch(danny.id, danny.name, listOf("tv9"), "u1234567", "סטיב")),
            visit = visit, connected = KeyClient.Tv("tv9", "DannyTV", danny),
            nearby = listOf(Nearby("192.168.1.9:47123", KeyClient.Tv("tv9", "DannyTV", danny)), Nearby("192.168.1.7:47123", KeyClient.Tv("tv7", "OurTV", home)), Nearby("192.168.1.5:47123", KeyClient.Tv("tv5", "NewTV", null))),
            pending = listOf(pending to "Strands"), link = AppLink.Ready.YES,
        )
        rule.setContent { AlHasapaTheme { CouchesScreen(view, actions) } }
        // The home: in words and with its mark.
        rule.onNodeWithText("🏠 ספת הבית שלי").assertIsDisplayed()
        rule.onNodeWithText(TestCouch.NAME).assertIsDisplayed()
        rule.onNodeWithText("מזהה " + Couches.short(home.id)).assertIsDisplayed()
        // What is connected this moment, apart from everything else.
        rule.onNode(hasTestTag(COUCH_CONNECTED_TAG)).assertIsDisplayed()
        rule.onNodeWithText("הטלפון מחובר עכשיו לטלוויזיה DannyTV · הספה של דני").assertIsDisplayed()
        // The visit, and the way out of it.
        rule.onNodeWithText("🛋️ מתארח בספה של דני").assertIsDisplayed()
        rule.onNodeWithText("בתור סטיב · מחובר לטלוויזיה").assertIsDisplayed()
        rule.onAllNodesWithText("⏳ תוצאת Strands מחכה לשליחה לספה של דני").onFirst().assertIsDisplayed()
        rule.onNode(hasTestTag(COUCH_END_VISIT_TAG)).performClick()
        assertEquals(listOf("end"), pressed)
        // A saved couch, with who the holder is there.
        rule.onNodeWithText("🛋️ ספה שמורה").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("מזהה " + Couches.short(danny.id) + " · אצלם אני סטיב").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("להגדיר כספת הבית שלי").performScrollTo().performClick()
        rule.onNodeWithText("הסרה מהרשימה").performScrollTo().performClick()
        assertEquals(listOf("end", "home:${danny.id}", "remove:${danny.id}"), pressed)
        // The TVs on this network: found, to be chosen; each says whose it is.
        rule.onNodeWithText("טלוויזיה שנמצאה ברשת לא מחוברת עד שבוחרים בה.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("🏠 הטלוויזיה של ספת הבית").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("טלוויזיה חדשה, עוד בלי ספה").performScrollTo().performClick()
        assertEquals("pick:tv5", pressed.last())
        // The app for a friend: offered because both downloads are there.
        rule.onNodeWithText("✓ מוכן לשליחה").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("שליחה").performScrollTo().performClick()
        assertEquals("share", pressed.last())
        save("phone-couches.png")
    }

    @Test
    fun theLinkIsNotOfferedUntilBothDownloadsAreThere() {
        var view = CouchesView(home = home, homeTvs = 0, saved = emptyList(), visit = null, link = AppLink.Ready.NO)
        rule.setContent { AlHasapaTheme { CouchesScreen(view, actions) } }
        rule.onNodeWithText("הקישור עוד לא מוכן: שתי ההורדות (טלפון וטלוויזיה) עוד לא זמינות בו.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("שליחה").assertDoesNotExist()
        rule.onNodeWithText("לבדוק שוב").performScrollTo().performClick()
        assertEquals(listOf("check"), pressed)
        // With nothing connected and no TV: said in words.
        rule.onNodeWithText("הטלפון לא מחובר עכשיו לאף טלוויזיה").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("לספת הבית עוד אין טלוויזיה.").performScrollTo().assertIsDisplayed()
        save("phone-couches-empty.png")
        // The link itself: one of three answers, and "ready" only when both files answer.
        assertEquals(AppLink.Ready.YES, AppLink.check { true })
        assertEquals(AppLink.Ready.NO, AppLink.check { url -> url == AppLink.PHONE })
        assertEquals(AppLink.Ready.NO, AppLink.check { url -> if (url == AppLink.TV) false else null })
        assertEquals(AppLink.Ready.UNKNOWN, AppLink.check { null })
        // What is sent: the page of the latest release, and nothing of a couch.
        val text = rule.activity.getString(R.string.share_app_text, AppLink.PAGE)
        assertTrue(text.contains("https://github.com/stevenbarzilay-cloud/Al-hasapa/releases/latest"))
        assertFalse(text.contains(home.id) || text.contains("סטיב") || text.contains("רוני"))
        assertTrue(AppLink.PHONE.endsWith("/releases/latest/download/al-hasapa-phone.apk") && AppLink.TV.endsWith("/releases/latest/download/al-hasapa-tv.apk"))
    }

    @Test
    fun theVisitsLineSaysWhoseCouchWhetherItsTvIsReachedAndHowToLeave() {
        val visit = Visit(danny.id, danny.name, "tv9", "u1234567", "סטיב", "2026-11-27", 1)
        var connected by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            AlHasapaTheme {
                VisitBar(visit, connected, pending = listOf("Strands"), contests = listOf(OpenContest("connections", "Connections", "דני, מיה")),
                    onEnd = { pressed += "end" }, onOpen = { pressed += "open" }, onJoin = { pressed += "join:$it" })
            }
        }
        rule.onNodeWithText("🛋️ מתארח בספה של דני").assertIsDisplayed()
        rule.onNodeWithText("🔗 מחובר לטלוויזיה · בתור סטיב").assertIsDisplayed()
        rule.onNodeWithText("⏳ תוצאת Strands מחכה לשליחה לספה של דני").assertIsDisplayed()
        rule.onNodeWithText("⚔️ תחרות Connections: דני, מיה").assertIsDisplayed()
        rule.onNodeWithText("להצטרף לתחרות Connections בספה של דני").performClick()
        // The network dropped: the line says so the moment the connection knows.
        connected = false
        rule.waitForIdle()
        rule.onNodeWithText("⚠️ אין חיבור לטלוויזיה · בתור סטיב").assertIsDisplayed()
        save("phone-visit-bar.png")
        rule.onNode(hasTestTag(VISIT_END_TAG)).performClick()
        assertEquals(listOf("join:connections", "end"), pressed)
    }

    @Test
    fun aTvWithNoCouchYetOffersToBeginOneOnThePhoneAndAnEmptyOneToBeJoined() {
        var step by androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            AlHasapaTheme {
                when (step) {
                    0 -> JoinDialog(KeyClient.Tv("tv5", "NewTV", null), onJoin = { pressed += "join" }, onCreate = { pressed += "create"; step = 1 }, onDismiss = {})
                    1 -> JoinDialog(KeyClient.Tv("tv5", "NewTV", CouchRef("c9999999999999ee", "הספה שלנו"), empty = true), onJoin = { pressed += "join"; step = 2 }, onCreate = {}, onDismiss = {})
                    else -> WelcomeScreen(tv = false, tvName = "", onCreate = { _, _, _ -> }, onJoin = {}, create = true)
                }
            }
        }
        rule.onNodeWithText("בטלוויזיה הזאת עוד לא הוקמה ספה", substring = true).assertIsDisplayed()
        rule.onNode(hasTestTag("join-home")).assertDoesNotExist()
        save("phone-join-no-couch.png", dialog = true)
        rule.onNode(hasTestTag("create-here")).performClick(); rule.waitForIdle()
        rule.onNodeWithText("טלוויזיה חדשה, עוד בלי ספה").assertIsDisplayed()
        rule.onNodeWithText("הספה שהוקמה בה עוד ריקה", substring = true).assertIsDisplayed()
        rule.onNode(hasTestTag("join-home")).performClick(); rule.waitForIdle()
        // Straight to naming a new couch.
        rule.onNode(hasTestTag(SETUP_A_TAG)).assertIsDisplayed()
        assertEquals(listOf("create", "join"), pressed)
    }

    @Test
    fun whichCouchAGamePlayedAloneIsForIsAskedWithBothNamedInFull() {
        val h = SoloTarget(home.id, home.name, "a", "סטיב", home = true)
        val v = SoloTarget(danny.id, danny.name, "u1234567", "סטיב", home = false)
        val picked = ArrayList<SoloTarget>()
        rule.setContent { AlHasapaTheme { CouchTargetDialog("Connections", h, v, onPick = { picked += it }, onDismiss = {}) } }
        rule.onNodeWithText("לאיזו ספה לשייך את התוצאה שלך ב־Connections?").assertIsDisplayed()
        rule.onNodeWithText("הספה של סטיב ורוני · הבית שלי").assertIsDisplayed()
        rule.onNodeWithText("הספה של דני · מתארח כאן").assertIsDisplayed()
        save("phone-couch-target.png", dialog = true)
        rule.onNode(hasTestTag(TARGET_VISIT_TAG)).performClick()
        rule.onNode(hasTestTag(TARGET_HOME_TAG)).performClick()
        assertEquals(listOf(v, h), picked)
    }

    @Test
    fun aFriendsTvOffersAnEveningOrToBeKeptAndTheHomeOnlyInSoManyWords() {
        var step by androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            AlHasapaTheme {
                when (step) {
                    0 -> VisitOptionsDialog(danny, "DannyTV", onEvening = { pressed += "evening" }, onKeep = { pressed += "keep" }, onHome = { step = 1 }, onDismiss = {})
                    1 -> SetHomeDialog(danny, home, onConfirm = { pressed += "home"; step = 2 }, onDismiss = {})
                    else -> GuestWhoDialog(danny, listOf(Person("a", "דני", "", "", 1), Person("b", "מיה", "", "", 1)), null, pieces, onPick = { pressed += "pick:${it.name}" }, onNew = { pressed += "new:$it" }, onDismiss = {})
                }
            }
        }
        rule.onNodeWithText("הספה של דני").assertIsDisplayed()
        rule.onNodeWithText("לשחק כאן כאורח הערב").assertIsDisplayed()
        rule.onNodeWithText("לשמור את הספה לביקורים הבאים").assertIsDisplayed()
        rule.onNodeWithText("שתי האפשרויות לא משנות את ספת הבית שלך.").assertIsDisplayed()
        save("phone-visit-options.png", dialog = true)
        rule.onNode(hasTestTag("visit-evening")).performClick()
        rule.onNode(hasTestTag("visit-keep")).performClick()
        // The home: a separate choice, and a confirmation that names the new home and says what it means.
        rule.onNodeWithText("להגדיר כספת הבית שלי").performClick(); rule.waitForIdle()
        rule.onNodeWithText("להגדיר את הספה של דני כספת הבית שלך?").assertIsDisplayed()
        rule.onNodeWithText("מעכשיו משחקי סולו חדשים ישויכו אליה כברירת מחדל. התוצאות הישנות נשארות בספה של סטיב ורוני ולא עוברות.").assertIsDisplayed()
        save("phone-set-home.png", dialog = true)
        rule.onNode(hasTestTag("set-home-yes")).performClick(); rule.waitForIdle()
        // Who plays from this phone, there: one of that couch's players, or someone new.
        rule.onNodeWithText("מי משחק מהטלפון הזה?").assertIsDisplayed()
        rule.onNodeWithText("מיה").performClick()
        rule.onNode(hasTestTag("who-ok")).performClick()
        rule.onNode(hasTestTag("who-new")).performClick()
        rule.onNode(hasTestTag("who-name")).performTextInput("סטיב")
        save("phone-guest-who.png", dialog = true)
        rule.onNode(hasTestTag("who-ok")).performClick()
        assertEquals(listOf("evening", "keep", "home", "pick:מיה", "new:סטיב"), pressed)
    }

    @Test
    fun aContestOfSeveralSaysWhoHandedInWhoIsStillPlayingAndWhereItCounts() {
        fun line(who: String, name: String, result: String, sealed: Boolean = false, has: Boolean = true) =
            SoloLine(who, name, pieces.styleOf(Person(who, name, "", "", 0)), result, sealed, has)
        var o by androidx.compose.runtime.mutableStateOf(
            // The guest handed in; Danny handed in; Maya joined and is still playing: results open, the crown waits.
            SoloOutcome("Connections", line("u1234567", "סטיב", "1 טעויות"), listOf(line("a", "דני", "2 טעויות"), line("b", "מיה", "", has = false)), null, couch = "הספה של דני", waiting = listOf("מיה")),
        )
        rule.setContent {
            AlHasapaTheme {
                Box(Modifier.fillMaxSize().background(Palette.Ink.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    SoloCard(o, other = null, reduceMotion = true, onDone = {}, onWithdraw = { pressed += "withdraw" })
                }
            }
        }
        rule.onNodeWithText("Connections · הספה של דני").assertIsDisplayed()
        rule.onNodeWithText("⏳ מחכים לתוצאה").assertIsDisplayed()
        rule.onNodeWithText("מחכים עוד למיה").assertIsDisplayed()
        save("phone-solo-three-waiting.png")
        // Kept on the phone, not sent yet: said, with where it will go.
        o = SoloOutcome("Connections", line("u1234567", "סטיב", "1 טעויות"), listOf(line("a", "דני", "", sealed = true)), null, couch = "הספה של דני", pending = true)
        rule.waitForIdle()
        rule.onNodeWithText("התוצאה שמורה בטלפון. היא תישלח לספה של דני כשהטלפון יתחבר שוב לטלוויזיה שלה, ולא תיספר בשום ספה אחרת.").assertIsDisplayed()
        save("phone-solo-pending.png")
        // A tie between two of three.
        o = SoloOutcome("Connections", line("u1234567", "סטיב", "1 טעויות"), listOf(line("a", "דני", "1 טעויות"), line("b", "מיה", "3 טעויות")), "t", couch = "הספה של דני", level = listOf("סטיב", "דני"))
        rule.waitForIdle()
        rule.onNodeWithText("🤝 תיקו בין סטיב לדני").assertIsDisplayed()
        save("phone-solo-three-tie.png")
        // Joined with no result: leaving the contest is offered.
        o = SoloOutcome("Connections", line("u1234567", "סטיב", "", has = false), listOf(line("a", "דני", "", sealed = true)), null, couch = "הספה של דני")
        rule.waitForIdle()
        rule.onNodeWithText("לפרוש מהתחרות (בלי תוצאה)").performClick()
        assertEquals(listOf("withdraw"), pressed)
    }
}

/** The first screen of a new device, and the visit's line over the phone's home (2.0), in the app itself. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class CouchesInTheAppTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app get() = context as CouchApp
    private var scenario: ActivityScenario<*>? = null

    @Before fun clean() {
        CrashReport.clear(context); Flight.acknowledgePrevious(); Links.reset()
        // (Android 17 asks the person once to let the app reach devices at home; here it was allowed.)
        shadowOf(context as android.app.Application).grantPermissions("android.permission.ACCESS_LOCAL_NETWORK")
    }
    @After fun close() { scenario?.close(); Links.reset(); CouchesActivity.scanner = null; CouchesActivity.probe = null; RemoteStore(context).me = null }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle(); rule.waitForIdle()
            if (ok()) return
            Thread.sleep(20)
        }
        org.junit.Assert.fail("timed out waiting for: $what")
    }

    @Test
    fun aNewDeviceOpensOnTheChoiceOfACouchAndNamesNobody() {
        assertTrue("nothing was chosen on this device", app.repo.peek { Couches.needsSetup(it) })
        scenario = ActivityScenario.launch(MainActivity::class.java); rule.waitForIdle()
        rule.onNodeWithText("הקמת ספה משלי").assertIsDisplayed()
        rule.onNodeWithText("הצטרפות לספה קיימת").assertIsDisplayed()
        rule.onAllNodesWithText("סטיב", substring = true).assertCountEqualsZero()
        rule.onAllNodesWithText("רוני", substring = true).assertCountEqualsZero()
        if (BuildConfig.TV) {
            // A TV begins its couch with one press; the first phone that joins names it.
            rule.onNode(hasTestTag(WELCOME_CREATE_TAG)).performClick(); rule.waitForIdle()
            assertNotNull(app.repo.peek { Couches.of(it) })
            assertEquals("הספה שלנו", app.repo.peek { Couches.name(it) })
            return
        }
        rule.onNode(hasTestTag(WELCOME_CREATE_TAG)).performClick(); rule.waitForIdle()
        rule.onNode(hasTestTag(SETUP_A_TAG)).performTextInput("נועה")
        rule.onNode(hasTestTag(SETUP_B_TAG)).performTextInput("יואב")
        rule.onNodeWithText("אם משאירים ריק: הספה של נועה ויואב").assertIsDisplayed()
        rule.onNode(hasTestTag(SETUP_START_TAG)).performScrollTo().performClick(); rule.waitForIdle()
        // A couch of their own: its name, its two players, nothing played; and who holds this phone is asked.
        assertEquals("הספה של נועה ויואב", app.repo.peek { Couches.of(it)!!.name })
        assertEquals("נועה" to "יואב", CouchState.names(app.repo.snapshot()))
        assertEquals(0, app.repo.snapshot().getJSONObject("log").length())
        assertFalse(app.repo.json().contains("סטיב") || app.repo.json().contains("רוני"))
        rule.onNodeWithText("מי משחק מהטלפון הזה?").assertIsDisplayed()
        rule.onAllNodesWithText("נועה").onFirst().assertIsDisplayed()
    }

    @Test
    fun aNewPhoneJoiningGoesToTheListOfTvsToChooseFrom() {
        org.junit.Assume.assumeFalse(BuildConfig.TV)
        scenario = ActivityScenario.launch(MainActivity::class.java); rule.waitForIdle()
        rule.onNode(hasTestTag(WELCOME_JOIN_TAG)).performClick(); rule.waitForIdle()
        val next = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(CouchesActivity::class.java.name, next.component!!.className)
        assertTrue(next.getBooleanExtra(CouchesActivity.EXTRA_JOIN, false))
    }

    @Test
    fun theTvAppOnAPhoneSaysSoFirstAndGoesOnOnlyWhenAsked() {
        TvMode.onPhoneForTest = true
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java); rule.waitForIdle()
            rule.onNodeWithText("זו האפליקציה לטלוויזיה").assertIsDisplayed()
            rule.onNodeWithText("להסרת האפליקציה הזאת", substring = true).assertIsDisplayed()
            rule.onNode(hasTestTag(WELCOME_CREATE_TAG)).assertDoesNotExist()
            // To be removed: the system's own screen for this app.
            rule.onNode(hasTestTag("wrong-app-remove")).performClick(); rule.waitForIdle()
            val next = shadowOf(context as android.app.Application).nextStartedActivity
            assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, next.action)
            assertEquals("package:${context.packageName}", next.dataString)
            // Going on all the same: said once, and the app is as it is on a TV.
            rule.onNode(hasTestTag("wrong-app-go-on")).performScrollTo().performClick(); rule.waitForIdle()
            rule.onNodeWithText("זו האפליקציה לטלוויזיה").assertDoesNotExist()
            rule.onNode(hasTestTag(WELCOME_CREATE_TAG)).assertIsDisplayed()
            assertFalse(TvMode.wrongApp(context))
        } finally {
            TvMode.onPhoneForTest = null
            TvMode.forget(context)
        }
    }

    @Test
    fun aHomeCouchWithNoTvSaysTheNextStepUntilItIsPutOff() {
        org.junit.Assume.assumeFalse(BuildConfig.TV)
        TestCouch.ours()
        RemoteStore(context).me = "a"
        Links.factory = { ctx, target -> KeyClient(ctx, target = target, finder = { _, _ -> emptyList() }) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("the next step, over the home") { rule.onAllNodes(hasTestTag(CONNECT_TV_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("📺 עוד לא חיברתם טלוויזיה").assertIsDisplayed()
        rule.onNodeWithText("לחבר טלוויזיה").performClick(); rule.waitForIdle()
        assertEquals(CouchesActivity::class.java.name, shadowOf(context as android.app.Application).nextStartedActivity.component!!.className)
        rule.onNode(hasTestTag(CONNECT_TV_LATER_TAG)).performClick(); rule.waitForIdle()
        rule.onNode(hasTestTag(CONNECT_TV_TAG)).assertDoesNotExist()
        assertTrue("put off: not shown again", CouchStore(context).tvLater)
    }

    @Test
    fun aHomeCouchWithItsTvSaysNothingOfIt() {
        org.junit.Assume.assumeFalse(BuildConfig.TV)
        TestCouch.ours()
        RemoteStore(context).me = "a"
        CouchStore(context).homeTvs = setOf("tv0001")
        Links.factory = { ctx, target -> KeyClient(ctx, target = target, finder = { _, _ -> emptyList() }) }
        scenario = ActivityScenario.launch(MainActivity::class.java); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(8_000); rule.waitForIdle()
        rule.onNode(hasTestTag(CONNECT_TV_TAG)).assertDoesNotExist()
    }

    @Test
    fun duringAVisitItsLineStaysOverTheHomeAndEndingItBringsThePhoneHome() {
        org.junit.Assume.assumeFalse(BuildConfig.TV)
        TestCouch.ours()
        RemoteStore(context).me = "a"
        val couches = CouchStore(context)
        couches.homeTvs = setOf("tv0001")
        couches.visit = Visit("c1111111111111aa", "הספה של דני", "tv0009", "u1234567", "סטיב", Couple.evening(), 1)
        // (No TV is there in this test: the line says so.)
        Links.factory = { ctx, target -> KeyClient(ctx, target = target, finder = { _, _ -> emptyList() }) }
        scenario = ActivityScenario.launch(MainActivity::class.java); rule.waitForIdle()
        rule.onNode(hasTestTag(VISIT_BAR_TAG)).assertIsDisplayed()
        rule.onNodeWithText("🛋️ מתארח בספה של דני").assertIsDisplayed()
        rule.onNodeWithText("⚠️ אין חיבור לטלוויזיה · בתור סטיב").assertIsDisplayed()
        assertEquals("the connection is the visit's", setOf("tv0009"), Links.targetFor(context)!!.tvs)
        rule.onNode(hasTestTag(VISIT_END_TAG)).performClick(); rule.waitForIdle()
        rule.onNode(hasTestTag(VISIT_BAR_TAG)).assertDoesNotExist()
        assertNull(couches.visit)
        assertEquals("at home again", setOf("tv0001"), Links.targetFor(context)!!.tvs)
        assertEquals("the home is what it was", TestCouch.ID, Couches.of(app.repo.snapshot())!!.id)
    }

    private class Memory : StateStore {
        @Volatile var text: String? = null
        override fun read() = text
        override fun write(json: String) { text = json }
    }

    @Test
    fun onMyCouchesAFriendsTvIsChosenItsCodeTypedAndTheHolderSaysWhoTheyAreThere() {
        org.junit.Assume.assumeFalse(BuildConfig.TV)
        TestCouch.ours()
        RemoteStore(context).me = "a"
        val danny = CouchRef("c1111111111111aa", "הספה של דני")
        val tvRepo = StateRepository(Memory()).also { r -> r.update { s -> Couple.put(s, Person("a", "דני", "", "", 5)); Couple.put(s, Person("b", "מיה", "", "", 5)); Couches.set(s, danny.id, danny.name, 5) } }
        val tvStore = RemoteStore(context, "tv-danny")
        val tv = KeyServer(
            context, store = tvStore, onInput = {}, preferredPort = 0, advertise = false, tvName = "DannyTV", onLogins = { null },
            onLinked = {}, frameSource = { null }, onMirroring = {}, book = RepoCoupleBook { tvRepo }, relay = { null }, onGuestSide = {},
        )
        tv.start()
        try {
            val address = "127.0.0.1:${tv.port}"
            CouchesActivity.scanner = { listOf(Nearby(address, KeyClient.Tv(tvStore.tvId, "DannyTV", danny))) }
            CouchesActivity.probe = AppLink.Probe { true }
            Links.factory = { ctx, target -> KeyClient(ctx, target = target, finder = { _, _ -> listOf(address) }) }
            scenario = ActivityScenario.launch(CouchesActivity::class.java); rule.waitForIdle()
            rule.onNodeWithText("🏠 ספת הבית שלי").assertIsDisplayed()
            waitFor("nothing is connected, said in words") { rule.onAllNodesWithText("הטלפון לא מחובר עכשיו לאף טלוויזיה").fetchSemanticsNodes().isNotEmpty() }
            waitFor("the TV on this network is listed") { rule.onAllNodesWithText("📺 DannyTV").fetchSemanticsNodes().isNotEmpty() }
            assertTrue("found is not connected: the TV was asked nothing", tv.ui.value.phones.isEmpty() && tv.ui.value.code == null)
            rule.onNodeWithText("📺 DannyTV").performScrollTo().performClick(); rule.waitForIdle()
            rule.onNodeWithText("לשחק כאן כאורח הערב").assertIsDisplayed()
            rule.onNode(hasTestTag("visit-evening")).performClick()
            waitFor("the TV shows its code") { tv.ui.value.code != null && rule.onAllNodes(hasTestTag(COUCH_CODE_TAG)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(KeyLink.AS_EVENING, tv.ui.value.codeAs)
            rule.onNode(hasTestTag(COUCH_CODE_TAG)).performTextInput(tv.ui.value.code!!)
            rule.onNode(hasTestTag("$COUCH_CODE_TAG-ok")).performClick()
            waitFor("who plays from this phone, there") { rule.onAllNodes(hasTestTag("who-new")).fetchSemanticsNodes().isNotEmpty() && rule.onAllNodesWithText("מיה").fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasTestTag("who-new")).performClick()
            rule.onNode(hasTestTag("who-name")).performTextInput("סטיב")
            rule.onNode(hasTestTag("who-ok")).performClick()
            waitFor("the visit is on, and its TV names the guest") { CouchStore(context).visitNow() != null && tvRepo.peek { s -> Couple.all(s).any { it.name == "סטיב" && it.guest } } }
            rule.onNodeWithText("🛋️ מתארח בספה של דני").performScrollTo().assertIsDisplayed()
            waitFor("connected now, said apart") { rule.onAllNodesWithText("הטלפון מחובר עכשיו לטלוויזיה DannyTV · הספה של דני").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("the home is the home", TestCouch.ID, Couches.of(app.repo.snapshot())!!.id)
            assertTrue("and has none of Danny's players", Couple.all(app.repo.snapshot()).none { it.name == "דני" || it.name == "מיה" })
            // The way out.
            rule.onNode(hasTestTag(COUCH_END_VISIT_TAG)).performScrollTo().performClick()
            waitFor("the TV is let go of at once") { tv.ui.value.phones.isEmpty() && CouchStore(context).visit == null }
            rule.onNodeWithText("🛋️ מתארח בספה של דני").assertDoesNotExist()
        } finally { tv.stop() }
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() = assertEquals(0, fetchSemanticsNodes().size)
