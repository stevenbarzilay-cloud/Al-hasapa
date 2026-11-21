package app.couch.alhasapa.player

import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.player.NavigationPolicy.Decision
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {
    private val nyt = setOf("nytimes.com")

    private fun decide(url: String, hosts: Set<String> = nyt, roaming: Boolean = false) = NavigationPolicy.decide(url, hosts, roaming)

    @Test
    fun staysOnTheGamesOwnSiteAndItsSubdomains() {
        assertEquals(Decision.Allow, decide("https://www.nytimes.com/games/wordle/index.html"))
        assertEquals(Decision.Allow, decide("https://myaccount.nytimes.com/auth/login?x=1"))
        assertEquals(Decision.Allow, decide("https://NYTIMES.com/"))
    }

    @Test
    fun otherSitesAreRefusedNotFollowed() {
        assertEquals(Decision.OffDomain("ads.doubleclick.net"), decide("https://ads.doubleclick.net/x"))
        assertEquals(Decision.OffDomain("nytimes.com.evil.org"), decide("https://nytimes.com.evil.org/"))
        assertEquals(Decision.OffDomain("evilnytimes.com"), decide("https://evilnytimes.com/"))
        assertEquals(Decision.OffDomain("www.washingtonpost.com"), decide("https://www.washingtonpost.com/a"))
    }

    @Test
    fun neverLaunchesAnotherApp() {
        for (u in listOf("intent://scan/#Intent;scheme=zxing;end", "market://details?id=com.nytimes.android", "nytimes://games",
            "linkedin://profile", "mailto:a@b.c", "tel:123", "sms:123", "file:///sdcard/x", "content://x", "javascript:alert(1)", "fb://page/1")) {
            assertTrue("$u must not be followed", decide(u) is Decision.BlockedScheme)
            assertTrue("even while roaming: $u", decide(u, roaming = true) is Decision.BlockedScheme)
        }
    }

    @Test
    fun roamingAllowsAnyWebPageForLoginButStillNoApps() {
        assertEquals(Decision.Allow, decide("https://accounts.some-sso.example/login", roaming = true))
        assertEquals(Decision.Allow, decide("http://example.org", roaming = true))
        assertTrue(decide("https://accounts.some-sso.example/login") is Decision.OffDomain)
    }

    @Test
    fun garbageIsRefusedWithoutCrashing() {
        assertTrue(decide("not a url at all") is Decision.BlockedScheme)
        assertTrue(decide("") is Decision.BlockedScheme)
        assertEquals(Decision.Allow, decide("about:blank"))
    }

    @Test
    fun theRealCatalogueGamesCanReachTheirOwnPages() {
        val catalog = Catalog.parse(File("src/main/assets/games.json").readText())
        for (g in catalog.games.filter { !it.placeholder }) {
            val hosts = catalog.allowedHosts(g, g.u)
            assertEquals("${g.id}: ${g.u}", Decision.Allow, NavigationPolicy.decide(g.u, hosts, false))
        }
    }
}
