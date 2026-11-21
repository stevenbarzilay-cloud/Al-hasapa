package app.couch.alhasapa.accounts

import app.couch.alhasapa.accounts.LoginDetector.Status
import app.couch.alhasapa.catalog.Catalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginDetectorTest {
    private val catalog = Catalog.parse(File("src/main/assets/games.json").readText())
    private val nyt = catalog.sites["nyt"]!!
    private val li = catalog.sites["li"]!!
    private val hz = catalog.sites["hz"]!!

    @Test
    fun knownSessionCookiesDecideForNytAndLinkedIn() {
        assertEquals(Status.LOGGED_IN, LoginDetector.status("a=1; NYT-S=0abc; b=2", nyt))
        assertEquals(Status.LOGGED_OUT, LoginDetector.status("a=1; b=2", nyt))
        assertEquals(Status.LOGGED_IN, LoginDetector.status("bcookie=x; li_at=AQED; JSESSIONID=y", li))
        assertEquals(Status.LOGGED_OUT, LoginDetector.status("bcookie=x; JSESSIONID=y", li))
    }

    @Test
    fun noCookiesAtAllIsLoggedOut() {
        assertEquals(Status.LOGGED_OUT, LoginDetector.status(null, hz))
        assertEquals(Status.LOGGED_OUT, LoginDetector.status("", nyt))
        assertEquals(Status.LOGGED_OUT, LoginDetector.status("  ;  ", li))
    }

    @Test
    fun anUnknownSiteSaysUnknownInsteadOfGuessing() {
        assertEquals(Status.UNKNOWN, LoginDetector.status("_ga=1; _gid=2; theme=dark", hz))
        assertEquals(Status.LOGGED_IN, LoginDetector.status("_ga=1; __utp=xyz", hz))
        assertEquals(Status.LOGGED_IN, LoginDetector.status("_ga=1; user_token=abc", hz))
    }

    @Test
    fun onlyNamesAreEverRead() {
        assertEquals(listOf("a", "NYT-S", "c"), LoginDetector.names("a=1; NYT-S=secret=with=equals; c"))
        assertEquals(emptyList<String>(), LoginDetector.names(null))
    }
}
