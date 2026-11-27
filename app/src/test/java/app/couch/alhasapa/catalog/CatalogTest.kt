package app.couch.alhasapa.catalog

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private val catalog = Catalog.parse(File("src/main/assets/games.json").readText())

    @Test
    fun everyGameOfThePrototypeIsThere() {
        // The prototype's 54, and the 14 that were new in the list of November 2026 (see the test of that list below).
        assertEquals(68, catalog.games.size)
        assertEquals("ids are unique", catalog.games.size, catalog.games.map { it.id }.toSet().size)
        for (g in catalog.games) assertNotNull("${g.id} needs its site ${g.s}", catalog.sites[g.s])
        assertEquals(12, catalog.sites.size)
    }

    @Test
    fun metricsAreTheOnesTheScoringKnows() {
        val ok = setOf("guesses", "mistakes", "hints", "time", "words", "score", "none")
        for (g in catalog.games) assertTrue("${g.id}: ${g.metric}", g.metric in ok)
        fun m(id: String) = catalog.game(id)!!.metric
        assertEquals("guesses", m("wordle")); assertEquals("mistakes", m("connections")); assertEquals("hints", m("strands"))
        assertEquals("time", m("queens")); assertEquals("guesses", m("pinpoint")); assertEquals("words", m("letterboxed"))
        assertEquals("mistakes", m("hzconn")); assertEquals("score", m("krillion")); assertEquals("score", m("dialed")); assertEquals("score", m("dozen")); assertEquals("guesses", m("hz5"))
    }

    @Test
    fun onlyTheLinkedInGamesAreHeadToHead() {
        assertEquals(setOf("queens", "tango", "zip", "pinpoint", "crossclimb", "minisudoku", "patches", "wend"),
            catalog.games.filter { it.isDuel }.map { it.id }.toSet())
    }

    @Test
    fun linksAreHttpsAndPlaceholdersAreFlagged() {
        for (g in catalog.games) assertTrue("${g.id} link must be https: ${g.u}", g.u.startsWith("https://"))
        assertEquals(setOf("midi", "noillirk", "moreless", "codenames", "cutle", "coffeegolf"),
            catalog.games.filter { it.placeholder }.map { it.id }.toSet())
    }

    @Test
    fun aPersonalLinkOverridesTheCatalogueAndIsAllowedOnItsOwnHost() {
        val g = catalog.game("hzchain")!!
        assertEquals(g.u, catalog.urlFor(g, JSONObject()))
        val state = JSONObject().put("urls", JSONObject().put("hzchain", "https://www.haaretz.co.il/riddles/chain-today"))
        assertEquals("https://www.haaretz.co.il/riddles/chain-today", catalog.urlFor(g, state))
        val q = catalog.game("framed")!!
        val own = "https://example.org/play"
        assertTrue("a direct link on any host is allowed for that game", Catalog.hostAllowed("example.org", catalog.allowedHosts(q, own)))
        assertFalse(Catalog.hostAllowed("ads.example.net", catalog.allowedHosts(q, own)))
    }

    @Test
    fun allowlistMatchesSubdomainsButNotLookalikes() {
        val g = catalog.game("wordle")!!
        val allowed = catalog.allowedHosts(g, g.u)
        assertTrue(Catalog.hostAllowed("www.nytimes.com", allowed))
        assertTrue(Catalog.hostAllowed("myaccount.nytimes.com", allowed))
        assertTrue(Catalog.hostAllowed("nytimes.com", allowed))
        assertFalse("lookalike", Catalog.hostAllowed("evilnytimes.com", allowed))
        assertFalse("lookalike", Catalog.hostAllowed("nytimes.com.evil.org", allowed))
        assertFalse(Catalog.hostAllowed(null, allowed))
        val hz = catalog.allowedHosts(catalog.game("hzchain")!!, catalog.game("hzchain")!!.u)
        assertTrue(Catalog.hostAllowed("www.haaretz.co.il", hz))
        assertTrue("payment and identity helpers", Catalog.hostAllowed("id.piano.io", hz))
    }

    @Test
    fun aPlaceholderMayRoamOnlyUntilItIsReplaced() {
        val g = catalog.game("midi")!!
        assertTrue(catalog.roams(g, g.u))
        assertFalse(catalog.roams(g, "https://www.nytimes.com/crosswords/game/midi"))
        assertFalse(catalog.roams(catalog.game("wordle")!!, catalog.game("wordle")!!.u))
    }

    @Test
    fun libraryFollowsThePersonsChoicesThenTheCatalogue() {
        val wordle = catalog.game("wordle")!!
        val letterboxed = catalog.game("letterboxed")!!
        val state = JSONObject().put("prefs", JSONObject().put("wordle", "no").put("letterboxed", "yes"))
        assertFalse(catalog.inLib(wordle, state)); assertTrue(catalog.inLib(letterboxed, state))
        assertTrue(catalog.inLib(wordle, null)); assertFalse(catalog.inLib(letterboxed, null))
    }

    @Test
    fun theAccountSitesKnowWhereToLogIn() {
        for (id in listOf("nyt", "hz", "li")) {
            val s = catalog.sites[id]!!
            assertTrue(id, s.account && s.domains.isNotEmpty() && s.loginUrl!!.startsWith("https://"))
        }
        assertEquals("player", catalog.sites["li"]!!.profile)
        assertEquals("games", catalog.sites["nyt"]!!.profile)
        assertNull(catalog.sites["kr"]!!.loginUrl)
    }

    @Test
    fun gamesAddedInTheSettingsJoinTheCatalogueAndBadOnesAreSkipped() {
        val custom = org.json.JSONArray("""[
          {"id":"c1","n":"Ours","s":"my","k":"מילים","m":"O","u":"https://games.example.org/x","metric":"time","lib":1},
          {"id":"bad1","n":"","s":"my","u":"https://x.example"},
          {"id":"bad2","n":"Plain http","s":"my","u":"http://x.example"},
          {"id":"wordle","n":"Duplicate of a built-in","s":"my","u":"https://x.example"},
          {"id":"c3","n":"On a site that does not exist","s":"nope","u":"https://y.example/play"},
          "not an object"]""")
        val c = catalog.withCustom(custom)
        assertEquals("two good ones", catalog.games.size + 2, c.games.size)
        val g = c.game("c1")!!
        assertEquals("time", g.metric); assertTrue(g.lib); assertEquals("my", g.s)
        assertEquals("a site that does not exist becomes our own", "my", c.game("c3")!!.s)
        assertNull(c.game("bad1")); assertNull(c.game("bad2"))
        assertEquals("a built-in keeps its place", "Wordle", c.game("wordle")!!.n)
        assertTrue("the player may stay on the new game's own site", c.allowedHosts(g, g.u).contains("games.example.org"))
        assertEquals(DecisionAllow, NavigationPolicyProbe.decide(g.u, c.allowedHosts(g, g.u)))
        assertSame("nothing to add is the same catalogue", catalog, catalog.withCustom(null))
        assertSame(catalog, catalog.withCustom(org.json.JSONArray()))
    }

    @Test
    fun theHubGetsOurGamesToo() {
        val c = catalog.withCustom(org.json.JSONArray("""[{"id":"c1","n":"Ours","s":"my","k":"ידע","m":"O","u":"https://games.example.org/x","metric":"none"}]"""))
        val hub = JSONObject(c.toHubJson(null))
        assertTrue(hub.getJSONObject("sites").has("my"))
        val ids = (0 until hub.getJSONArray("games").length()).map { hub.getJSONArray("games").getJSONObject(it).getString("id") }
        assertTrue("c1" in ids)
    }

    @Test
    fun theShelfAndTheSuggestionsAreTheListWeWereGivenLinkForLink() {
        // The permanent games: on the shelf from the start, each opening the address that was given, none of them a suggestion.
        val shelf = linkedMapOf(
            "songless" to "https://lessgames.com/songless", "framed" to "https://framed.wtf/", "catfishing" to "https://catfishing.net/",
            "originle" to "https://originle.io/", "dozen" to "https://dailydozentrivia.com/", "disorderly" to "https://playdisorderly.com/",
            "magsize" to "https://magnitudle.com/size-it-up", "bandle" to "https://bandle.app/",
        )
        for ((id, url) in shelf) {
            val g = catalog.game(id) ?: error("$id is missing")
            assertEquals(id, url, g.u); assertTrue("$id is on the shelf", g.lib); assertFalse(id, g.placeholder); assertNull("$id is not a suggestion", g.pick)
            assertTrue("$id says what it is", g.d.isNotBlank() && g.m.isNotBlank() && g.k.isNotBlank())
        }
        assertEquals("The Dozen: Daily Trivia", catalog.game("dozen")!!.n)
        assertEquals("Magnitudle: Size It Up", catalog.game("magsize")!!.n)

        // The ten suggestions: the name, the address and the sentence as given, offered first and in that order.
        val picks = listOf(
            Triple("Albumdle", "https://statt-land-song.ch/albumdle/", "מנחשים את האמן, האלבום ושנת היציאה לפי העטיפה."),
            Triple("Actorle", "https://actorle.com/", "מנחשים מי שיחק בסרטים ברשימה כששמות הסרטים מוסתרים."),
            Triple("TimeGuessr Daily", "https://timeguessr.com/play?mode=daily", "מנחשים איפה ובאיזו שנה צולם כל תצלום."),
            Triple("Thrice", "https://thrice.geekswhodrink.com/", "בכל סיבוב יש שלוש שאלות שמובילות לאותה תשובה."),
            Triple("The Beatles Daily", "https://playfliplist.com/daily/beatles/", "מזהים שיר של הביטלס משנייה אחת."),
            Triple("Flickle", "https://flickle.app/", "מזהים סרט מקטעי וידאו קצרים שנחשפים בהדרגה."),
            Triple(
                "Cinematrix", "https://www.vulture.com/article/daily-movie-grid-trivia-game-cinematrix.html",
                "ממלאים לוח 3×3 בסרטים שמתאימים גם לרמז של השורה וגם לרמז של העמודה.",
            ),
            Triple("Spotle", "https://spotle.io/", "מנחשים את האמן או הלהקה לפי רמזים שמתקבלים אחרי כל ניחוש."),
            Triple("Chrono Trivia", "https://chronotrivia.com/", "מסדרים אירועים לפי סדר כרונולוגי, עם קטגוריות כמו מוזיקה, בידור והיסטוריה."),
            Triple("Magnitudle: Weigh It Up", "https://magnitudle.com/weigh-it-up", "מעריכים כמה פריטים צריך כדי לאזן את המאזניים."),
        )
        val offered = Catalog.offered(catalog.games.filter { !it.lib })
        assertEquals("offered first, in the order given", picks.map { it.first }, offered.take(10).map { it.n })
        for ((i, p) in picks.withIndex()) {
            val g = offered[i]
            assertEquals(g.id, p.second, g.u); assertEquals(g.id, p.third, g.d); assertEquals(g.id, i + 1, g.pick)
            assertFalse("${g.id} is a suggestion, not on the shelf", g.lib); assertFalse("${g.id} has a real link now", g.placeholder)
        }
        assertEquals("the rest of the suggestions follow as the catalogue has them", catalog.games.filter { !it.lib && it.pick == null }, offered.drop(10))

        // Nothing is there twice: not a name, and not one of these addresses.
        assertEquals(catalog.games.size, catalog.games.map { it.n.lowercase() }.toSet().size)
        for (url in shelf.values + picks.map { it.second }) {
            assertEquals(url, 1, catalog.games.count { it.u.trimEnd('/').equals(url.trimEnd('/'), ignoreCase = true) })
        }
        // Each opens on its own site (the player keeps a game on the site its link is on), and the hub is told the order.
        for (g in shelf.keys.map { catalog.game(it)!! } + offered.take(10)) {
            assertEquals(g.id, DecisionAllow, NavigationPolicyProbe.decide(g.u, catalog.allowedHosts(g, g.u)))
        }
        val hub = JSONObject(catalog.toHubJson(null)).getJSONArray("games")
        val picked = (0 until hub.length()).map { hub.getJSONObject(it) }.filter { it.has("pick") }
        assertEquals((1..10).toList(), picked.map { it.getInt("pick") }.sorted())
    }

    @Test
    fun eachDialedCardOpensItsOwnGameNotTheSitesHomePage() {
        // Both used to open https://dialed.gg, a long showcase page (twice the size of a game's page, with animated demos) on which the
        // game still had to be found; on the TV box it did not come up at all. The site has a page for each game.
        assertEquals("https://dialed.gg/color", catalog.game("dialed")!!.u)
        assertEquals("https://dialed.gg/sound", catalog.game("dialedsound")!!.u)
        // The player stays on the site (its scripts come from cdn.dialed.gg, a subdomain).
        val hosts = catalog.allowedHosts(catalog.game("dialed")!!, catalog.game("dialed")!!.u)
        assertTrue(Catalog.hostAllowed("dialed.gg", hosts)); assertTrue(Catalog.hostAllowed("cdn.dialed.gg", hosts))
    }
}

private val DecisionAllow = app.couch.alhasapa.player.NavigationPolicy.Decision.Allow
private object NavigationPolicyProbe {
    fun decide(url: String, hosts: Set<String>) = app.couch.alhasapa.player.NavigationPolicy.decide(url, hosts, false)
}
