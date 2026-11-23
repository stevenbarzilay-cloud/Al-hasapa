package app.couch.alhasapa.remote

import app.couch.alhasapa.catalog.Catalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What goes from the phone's browser to the TV's for a site, and what the TV takes. */
class LoginHandoffTest {
    private val catalog = Catalog.parse(File("src/main/assets/games.json").readText())
    private val nyt = catalog.sites.getValue("nyt")

    private class FakeJar(val byUrl: Map<String, String> = emptyMap()) : LoginHandoff.Jar {
        val set = ArrayList<Pair<String, String>>()
        var flushed = 0
        override fun get(url: String): String? = byUrl[url]
        override fun set(url: String, setCookie: String) { set += url to setCookie }
        override fun flush() { flushed++ }
    }

    private val phone = FakeJar(
        mapOf(
            "https://nytimes.com/" to "NYT-S=0^abc; nyt-a=xyz",
            "https://www.nytimes.com/" to "NYT-S=0^abc; nyt-a=xyz; datadome=bot-check; nyt-gdpr=0",
            "https://myaccount.nytimes.com/" to "NYT-S=0^abc; __Host-csrf=tok",
        ),
    )

    @Test
    fun whichSitesCanGo() {
        assertTrue(LoginHandoff.canHand(nyt))
        assertTrue(LoginHandoff.canHand(catalog.sites.getValue("hz")))
        assertFalse("one login per person: not with the TV's one browser", LoginHandoff.canHand(catalog.sites.getValue("li")))
        assertFalse("no login at all", LoginHandoff.canHand(catalog.sites.getValue("kr")))
    }

    @Test
    fun theSitesCookiesGoOncePerNameForTheWholeSiteButNotTheAntiBotOnes() {
        val c = LoginHandoff.collect(nyt, phone)
        assertEquals(listOf("NYT-S", "nyt-a", "nyt-gdpr", "__Host-csrf"), c.map { it.line.substringBefore('=') })
        assertTrue(c.dropLast(1).all { it.url == "https://nytimes.com/" })
        assertEquals("a __Host- cookie stays on its own host, with no Domain (a browser refuses it otherwise)",
            LoginHandoff.Cookie("https://myaccount.nytimes.com/", "__Host-csrf=tok; Path=/; Secure; Max-Age=5184000"), c.last())
        assertEquals("NYT-S=0^abc; Domain=.nytimes.com; Path=/; Secure; Max-Age=5184000", c.first().line)
        assertEquals(true, LoginHandoff.signedIn(nyt, phone))
        assertEquals(false, LoginHandoff.signedIn(nyt, FakeJar(mapOf("https://www.nytimes.com/" to "nyt-a=xyz"))))
        // Haaretz: the name of its session cookie is not known, so cookies that say nothing either way are "cannot tell" (and may be sent).
        val hz = catalog.sites.getValue("hz")
        assertNull(LoginHandoff.signedIn(hz, FakeJar(mapOf("https://www.haaretz.co.il/" to "_ga=1; theme=light"))))
        assertEquals(false, LoginHandoff.signedIn(hz, FakeJar()))
    }

    @Test
    fun theTvTakesOnlyCookiesForTheSitesOwnDomainsOverHttps() {
        val json = LoginHandoff.payload("nyt", LoginHandoff.collect(nyt, phone))
        val (site, cookies) = LoginHandoff.parse(json, catalog.sites)!!
        assertEquals("nyt", site.id); assertEquals(4, cookies.size)
        val tv = FakeJar()
        assertEquals(4, LoginHandoff.apply(tv, cookies))
        assertEquals(1, tv.flushed)
        assertTrue(tv.set.all { it.first == "https://nytimes.com/" || it.first == "https://myaccount.nytimes.com/" })

        val sneaky = """{"site":"nyt","cookies":[{"u":"https://evil.example/","c":"x=1"},{"u":"http://nytimes.com/","c":"y=2"},{"u":"https://www.nytimes.com/","c":"z=3"}]}"""
        assertEquals(listOf("z=3"), LoginHandoff.parse(sneaky, catalog.sites)!!.second.map { it.line })
        assertNull("not a site that can go", LoginHandoff.parse("""{"site":"li","cookies":[]}""", catalog.sites))
        assertNull(LoginHandoff.parse("not json", catalog.sites))
    }
}
