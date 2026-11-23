package app.couch.alhasapa.accounts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class AccountsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val catalog = Catalog.parse(File("src/main/assets/games.json").readText())
    private val pieces = PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
    private val logins = mutableListOf<Triple<String, String, String>>()
    private val chrome = mutableListOf<String>()

    private var toTv = 0

    /** As on the phone ([phone]: a login can go on to the TV), as on the TV ([tv]), or plain. */
    private fun show(phone: Boolean = false, tv: Boolean = false) = rule.setContent {
        AlHasapaTheme {
            androidx.compose.runtime.CompositionLocalProvider(
                app.couch.alhasapa.tv.LocalTv provides if (tv) app.couch.alhasapa.tv.TvController(true, 2f) else null,
            ) {
                AccountsScreen(
                    sites = catalog.sites.values.filter { it.account }, nameA = "סטיב", nameB = "רוני",
                    pieceA = pieces.styleFor(null, "a"), pieceB = pieces.styleFor(null, "b"),
                    onLogin = { s, t, p -> logins += Triple(s.id, t, p) }, onChrome = { chrome += it }, onBack = {},
                    onSendToTv = if (phone) ({ toTv++ }) else null,
                )
            }
        }
    }

    @org.junit.After fun noCookiesLeft() { android.webkit.CookieManager.getInstance().removeAllCookies(null) }

    @Test
    fun onTheTvItSaysFirstThatTheEasyWayIsThePhoneAndHow() {
        show(tv = true)
        rule.onNodeWithText("הכי קל: מתחברים בטלפון ושולחים לכאן").assertIsDisplayed()
        rule.onNodeWithText("״מקלדת לטלוויזיה״", substring = true).assertIsDisplayed()
        rule.onNodeWithText("״התחברות לטלוויזיה״", substring = true).assertIsDisplayed()
        rule.onNodeWithText("3. ״לשלוח״, וההתחברות מגיעה לכאן מיד.").assertIsDisplayed()
        // Signing in on the TV itself is still there, and nothing here offers to send a login to the TV (this is the TV).
        assertEquals(4, rule.onAllNodesWithText("להתחבר").fetchSemanticsNodes().size)
        rule.onNodeWithText("לשלוח את ההתחברות לטלוויזיה").assertDoesNotExist()
    }

    @Test
    fun onThePhoneALoginMadeHereCanGoOnToTheTv() {
        // Signed in to NYT in this app's browser (the cookie the site keeps for a signed-in reader).
        android.webkit.CookieManager.getInstance().setCookie("https://www.nytimes.com", "NYT-S=0^abc; Domain=.nytimes.com; Path=/; Secure")
        show(phone = true)
        rule.onNodeWithText("גם בטלוויזיה", substring = true).assertIsDisplayed()
        // Only where there is a login to send, and only for the sites whose login can go (not the two LinkedIn accounts).
        assertEquals(1, rule.onAllNodesWithText("לשלוח את ההתחברות לטלוויזיה").fetchSemanticsNodes().size)
        rule.onNodeWithText("לשלוח את ההתחברות לטלוויזיה").performScrollTo().performClick()
        assertEquals(1, toTv)
        rule.onNodeWithText("הכי קל: מתחברים בטלפון ושולחים לכאן").assertDoesNotExist()
    }

    @Test
    fun withNothingSignedInThereIsNothingToSendYet() {
        show(phone = true)
        rule.onNodeWithText("לשלוח את ההתחברות לטלוויזיה").assertDoesNotExist()
    }

    @Test
    fun listsEveryAccountWithLinkedInOncePerPerson() {
        show()
        rule.onNodeWithText("החשבונות שלנו").assertIsDisplayed()
        rule.onNodeWithText("NYT").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("הארץ").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("LinkedIn · סטיב").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("LinkedIn · רוני").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun explainsTheGoogleSignInLimitInsteadOfWorkingAroundIt() {
        show()
        rule.onNodeWithText("גוגל לא מאפשרת", substring = true).assertIsDisplayed()
        rule.onNodeWithText("מייל וסיסמה", substring = true).assertIsDisplayed()
    }

    @Test
    fun everyAccountHasALoginAndAChromeFallback() {
        show()
        assertEquals(4, rule.onAllNodesWithText("להתחבר").fetchSemanticsNodes().size)
        assertEquals(4, rule.onAllNodesWithText("לפתוח בכרום").fetchSemanticsNodes().size)
    }

    @Test
    fun loginOpensTheRightSiteInTheRightProfile() {
        show()
        rule.onNodeWithText("LinkedIn · רוני").performScrollTo()
        rule.onAllNodesWithText("להתחבר")[3].performScrollTo().performClick()
        rule.onAllNodesWithText("להתחבר")[0].performScrollTo().performClick()
        assertEquals(Triple("li", "LinkedIn · רוני", "roni"), logins[0])
        assertEquals(Triple("nyt", "NYT", "games"), logins[1])
        rule.onAllNodesWithText("לפתוח בכרום")[0].performScrollTo().performClick()
        assertEquals("https://myaccount.nytimes.com/auth/login", chrome[0])
    }

    @Test
    fun saysSoWhenTheWebViewIsTooOldForSeparateAccounts() {
        // Robolectric reports no MULTI_PROFILE support, which is exactly the fallback case.
        show()
        rule.onNodeWithText("Android System WebView", substring = true).assertIsDisplayed()
    }

    @Test
    fun sitesWithNoCookiesYetAreNotLoggedIn() {
        show()
        assertEquals(4, rule.onAllNodesWithText("לא מחובר").fetchSemanticsNodes().size)
    }
}
