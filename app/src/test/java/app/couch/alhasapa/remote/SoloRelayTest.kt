package app.couch.alhasapa.remote

import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.BuildConfig
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.data.Solo
import app.couch.alhasapa.data.SoloSide
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.data.StateStore
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The solo relay (1.11): sealed boxes, the configuration the TV hands out, and two phones and a TV telling each other of the games
 * played alone through an ntfy topic (an in-memory one, and once a real HTTP server for the client itself).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class SoloRelayTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = LocalDate.of(2026, 11, 27)
    private val key = "2026-11-27"

    private class MemoryStore : StateStore {
        var text: String? = null
        override fun read() = text
        override fun write(json: String) { text = json }
    }

    private fun repo() = StateRepository(MemoryStore()) { day }

    /** ntfy as it answers: a message published gets the next id; a poll gives the messages after an id, or all for a duration. */
    private class FakeNtfy : RelayHttp {
        val messages = ArrayList<Pair<String, String>>()
        var down = false
        override fun post(url: String, body: String): Int {
            if (down) return -1
            messages += "m${messages.size + 1}" to body
            return 200
        }
        override fun get(url: String): String? {
            if (down) return null
            val since = url.substringAfter("since=")
            val from = messages.indexOfFirst { it.first == since }.let { if (it < 0) 0 else it + 1 }
            return buildString {
                append("""{"id":"o1","time":1,"event":"open","topic":"t"}""").append('\n')
                for ((id, text) in messages.drop(from)) append(JSONObject().put("id", id).put("time", 2).put("event", "message").put("message", text)).append('\n')
            }
        }
    }

    @Test
    fun aBoxOpensOnlyWithItsKeyAndPurposeAndUnchanged() {
        val k = ByteArray(32) { it.toByte() }
        val box = RelayCrypto.seal(k, "couch-solo v1 t", "שלום".toByteArray())
        assertEquals("שלום", String(RelayCrypto.open(k, "couch-solo v1 t", box)!!))
        assertNull("another key", RelayCrypto.open(ByteArray(32), "couch-solo v1 t", box))
        assertNull("another purpose", RelayCrypto.open(k, "couch-relay-config v1", box))
        val bytes = java.util.Base64.getDecoder().decode(box).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull("a changed byte", RelayCrypto.open(k, "couch-solo v1 t", java.util.Base64.getEncoder().encodeToString(bytes)))
        assertNull("not a box", RelayCrypto.open(k, "x", "@@@"))
    }

    @Test
    fun theConfigurationIsAnHttpsServerALongTopicAndAWholeKey() {
        val c = RelayConfig.create()
        assertEquals("https://ntfy.sh", c.base)
        assertTrue(c.topic.startsWith("couch-") && c.topic.length >= 22)
        assertEquals(c, RelayConfig.parse(c.toJson()))
        assertFalse("each one its own topic and key", c == RelayConfig.create())
        val ok = JSONObject(c.toJson())
        assertNull("plain http to another host", RelayConfig.parse(JSONObject(ok.toString()).put("base", "http://ntfy.sh").toString()))
        assertNull("a short topic", RelayConfig.parse(JSONObject(ok.toString()).put("topic", "couch").toString()))
        assertNull("half a key", RelayConfig.parse(JSONObject(ok.toString()).put("key", java.util.Base64.getEncoder().encodeToString(ByteArray(16))).toString()))
        assertNotNull("a server on this machine (the tests)", RelayConfig.parse(JSONObject(ok.toString()).put("base", "http://127.0.0.1:8080").toString()))
    }

    @Test
    fun twoPhonesAndTheTvTellEachOtherOfTheGamesPlayedAlone() {
        val cfg = RelayConfig.create()
        val net = FakeNtfy()
        var clock = 1_764_000_000_000L
        fun relay(name: String, repo: StateRepository) = SoloRelay(context, repo, net, { clock }, { day }, RelayStore(context, name).apply { config = cfg })
        val steveRepo = repo(); val roniRepo = repo(); val tvRepo = repo()
        val steve = relay("relay-a", steveRepo); val roni = relay("relay-b", roniRepo); val tv = relay("relay-tv", tvRepo)

        // Steve plays Bandle alone (3 of 6): his phone tells the relay; nothing readable goes there.
        steveRepo.update { Solo.put(it, key, "bandle", SoloSide("a", 3.0, 6, false, "guesses", clock), clock) }
        assertTrue(steve.send("a"))
        assertEquals(1, net.messages.size)
        assertFalse("sealed: no word of it on the wire", net.messages[0].second.contains("bandle"))

        // Roni's phone looks: the challenge, said once; the result is there, sealed by the app until Roni plays.
        assertEquals(listOf(SoloRelay.News(key, "bandle", "a", reveal = false)), roni.receive("b"))
        assertEquals(3.0, Solo.side(roniRepo.snapshot(), key, "bandle", "a")!!.v!!, 0.0)
        assertTrue("said once", roni.receive("b")!!.isEmpty())

        // Roni plays it alone (2 of 6): Steve's phone gets the reveal, once.
        clock += 60_000
        roniRepo.update { Solo.put(it, key, "bandle", SoloSide("b", 2.0, 6, false, "guesses", clock), clock) }
        assertTrue(roni.send("b"))
        assertEquals(listOf(SoloRelay.News(key, "bandle", "b", reveal = true)), steve.receive("a"))
        assertEquals("b", Solo.winner(steveRepo.snapshot(), key, "bandle", "guesses"))
        assertTrue(steve.receive("a")!!.isEmpty())

        // A box under another key, and one from "Roni" that carries a side of Steve's: neither is taken.
        net.messages += "m90" to RelayCrypto.seal(ByteArray(32), "couch-solo v1 ${cfg.topic}", """{"v":1,"who":"a","solo":{}}""".toByteArray())
        val forged = JSONObject().put("v", 1).put("who", "b").put("solo", JSONObject().put(key, JSONObject().put("bandle", JSONObject()
            .put("a", JSONObject().put("v", 6).put("max", 6).put("t", clock + 5_000)))))
        net.messages += "m91" to RelayCrypto.seal(cfg.key, "couch-solo v1 ${cfg.topic}", forged.toString().toByteArray())
        roni.receive("b")
        assertEquals("one phone never writes another person's game", 3.0, Solo.side(roniRepo.snapshot(), key, "bandle", "a")!!.v!!, 0.0)

        // The TV reads everything, tells nobody, and keeps both sides (for the reveal on its home).
        assertEquals(emptyList<SoloRelay.News>(), tv.receive(null))
        assertEquals(setOf("a", "b"), Solo.sides(tvRepo.snapshot(), key, "bandle").map { it.who }.toSet())
    }

    /**
     * 1.12: what the devices keep alike travels through the relay too, from anywhere: a result saved on the TV is on the phones' pages
     * with the same points; a tick taken back on a phone goes from the TV; a busy evening goes in parts, each within the relay's limit.
     */
    @Test
    fun aResultSavedOnTheTvReachesThePhonesThroughTheRelayAndBackAgain() {
        val cfg = RelayConfig.create()
        val net = FakeNtfy()
        var clock = 1_764_000_000_000L
        fun device(name: String): Pair<StateRepository, SoloRelay> {
            val r = repo().also { it.clock = { clock } }
            return r to SoloRelay(context, r, net, { clock }, { day }, RelayStore(context, name).apply { config = cfg })
        }
        fun entry(v: Int, raw: String = "") = JSONObject().put("w", "").put("t", clock)
            .put("r", JSONObject().put("v", v).put("max", 6).put("fail", false).put("kind", "guesses").apply { if (raw.isNotEmpty()) put("raw", raw) })
        fun pts(r: StateRepository) = app.couch.alhasapa.scoring.Scoring.dayPts(r.snapshot().optJSONObject("log") ?: JSONObject(), key)
        val (tvRepo, tv) = device("relay-s-tv"); val (roniRepo, roni) = device("relay-s-b"); val (steveRepo, steve) = device("relay-s-a")

        // The evening on the TV: three results, a game taken off the shelf.
        tvRepo.update { s ->
            app.couch.alhasapa.data.CouchState.setEntry(s, key, "wordle", entry(3, "Wordle 1,618 3/6"))
            app.couch.alhasapa.data.CouchState.setEntry(s, key, "bandle", entry(2))
            app.couch.alhasapa.data.CouchState.setEntry(s, key, "connections", entry(1))
            s.put("prefs", JSONObject().put("pips", "no"))
        }
        assertTrue(tv.sendShare("tv"))
        assertTrue("sealed: nothing of it readable on the wire", net.messages.none { it.second.contains("wordle") || it.second.contains("Wordle") })
        assertTrue("each message within the relay's limit", net.messages.all { it.second.length <= 4096 })
        // Each phone reads the relay, wherever it is: the same evening, the same points, the same shelf.
        roni.receive("b"); steve.receive("a")
        assertTrue(pts(tvRepo) > 0)
        assertEquals(pts(tvRepo), pts(roniRepo)); assertEquals(pts(tvRepo), pts(steveRepo))
        assertEquals("no", roniRepo.snapshot().getJSONObject("prefs").getString("pips"))
        assertFalse("the text of a result stays home", roniRepo.snapshot().toString().contains("1,618"))
        assertEquals("what came from the relay is not said back to it", 0L, RelayStore(context, "relay-s-b").shareSentAt)

        // Roni takes a tick back on the phone: it goes from the TV and from Steve's phone.
        clock += 60_000
        roniRepo.update { s -> app.couch.alhasapa.data.CouchState.setEntry(s, key, "bandle", null) }
        val before = net.messages.size
        assertTrue(roni.sendShare("b"))
        assertTrue(net.messages.size > before)
        tv.receive(null); steve.receive("a")
        assertNull(app.couch.alhasapa.data.CouchState.entry(tvRepo.snapshot(), key, "bandle"))
        assertNull(app.couch.alhasapa.data.CouchState.entry(steveRepo.snapshot(), key, "bandle"))
        assertEquals(pts(roniRepo), pts(tvRepo)); assertEquals(pts(roniRepo), pts(steveRepo))
        // Nothing new: nothing is sent.
        val quiet = net.messages.size
        assertTrue(roni.sendShare("b"))
        assertEquals(quiet, net.messages.size)

        // A very busy evening goes in parts.
        clock += 60_000
        tvRepo.update { s -> for (i in 1..40) app.couch.alhasapa.data.CouchState.setEntry(s, key, "game$i", entry(4)) }
        val at = net.messages.size
        assertTrue(tv.sendShare("tv"))
        assertTrue("in more than one message", net.messages.size - at > 1)
        assertTrue(net.messages.all { it.second.length <= 4096 })
        roni.receive("b")
        assertEquals(pts(tvRepo), pts(roniRepo))

        // The relay cannot be reached: the changes wait, and go with the next look.
        clock += 60_000
        net.down = true
        tvRepo.update { s -> app.couch.alhasapa.data.CouchState.setEntry(s, key, "strands", entry(1)) }
        assertFalse(tv.sendShare("tv"))
        assertTrue(RelayStore(context, "relay-s-tv").sharePending)
        net.down = false
        tv.sync(null)
        roni.receive("b")
        assertNotNull(app.couch.alhasapa.data.CouchState.entry(roniRepo.snapshot(), key, "strands"))
    }

    @Test
    fun whatCouldNotBeSentWaitsAndTodaysSidesAreSaidAgainBeforeTheRelayLetsThemGo() {
        val cfg = RelayConfig.create()
        val net = FakeNtfy()
        var clock = 1_764_000_000_000L
        val repo = repo()
        val store = RelayStore(context, "relay-a2").apply { config = cfg }
        val r = SoloRelay(context, repo, net, { clock }, { day }, store)
        repo.update { Solo.put(it, key, "dialed", SoloSide("a", 41.0, null, false, "score", clock), clock) }
        net.down = true
        assertFalse(r.send("a"))
        assertTrue("it waits", store.pending)
        assertTrue("and the relay could not be read either", r.receive("a") == null)
        net.down = false
        r.sync("a")
        assertEquals(1, net.messages.size)
        assertFalse(store.pending)
        r.sync("a")
        assertEquals("not again so soon", 1, net.messages.size)
        clock += SoloRelay.RESEND_MS + 1
        r.sync("a")
        assertEquals("said again before the relay's twelve hours are up", 2, net.messages.size)
    }

    @Test
    fun theClientPublishesAndReadsAnNtfyServerOverHttp() {
        val posted = ArrayList<String>()
        var asked = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val out = if (ex.requestMethod == "POST") {
                posted += String(ex.requestBody.readBytes(), Charsets.UTF_8)
                """{"id":"m1","time":2,"event":"message","message":"x"}"""
            } else {
                asked = ex.requestURI.toString()
                "{\"id\":\"o1\",\"time\":1,\"event\":\"open\"}\n{\"id\":\"m1\",\"time\":2,\"event\":\"message\",\"message\":\"hello\"}\n{\"id\":\"k1\",\"time\":3,\"event\":\"keepalive\"}\n"
            }
            val bytes = out.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val c = RelayClient(RelayConfig("http://127.0.0.1:${server.address.port}", "couch-abcdefghijklmnop", ByteArray(32)))
            assertTrue(c.publish("sealed box"))
            assertEquals(listOf("sealed box"), posted)
            val got = c.poll("12h")!!
            assertEquals("only the messages", listOf("m1"), got.map { it.id })
            assertEquals("hello", got[0].text)
            assertEquals("/couch-abcdefghijklmnop/json?poll=1&since=12h", asked)
        } finally { server.stop(0) }
        assertNull("nobody there", RelayClient(RelayConfig("http://127.0.0.1:9", "couch-abcdefghijklmnop", ByteArray(32))).poll("12h"))
    }

    @Test
    fun theNewsIsSaidOnThePhoneOncePerGameAndDay() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val app = context as CouchApp
        SoloNews.notify(context, app, listOf(SoloRelay.News(key, "bandle", "a", reveal = false)))
        val nm = context.getSystemService(NotificationManager::class.java)
        val first = shadowOf(nm).allNotifications.single()
        assertEquals("⚔️ סטיב כבר שיחק היום ב-Bandle", first.extras.getString(android.app.Notification.EXTRA_TITLE))
        // The reveal takes the challenge's place.
        SoloNews.notify(context, app, listOf(SoloRelay.News(key, "bandle", "b", reveal = true)))
        val n = shadowOf(nm).allNotifications.single()
        assertEquals("⚔️ חשיפה ב-Bandle!", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("גם רוני סיים. מי לוקח את הכתר?", n.extras.getString(android.app.Notification.EXTRA_TEXT))
    }

    @Test
    fun aPhoneWithTheRelayLooksByItselfEveryQuarterOfAnHour() {
        val js = context.getSystemService(JobScheduler::class.java)
        SoloJob.schedule(context)
        assertTrue("not without the relay", js.allPendingJobs.none { it.id == SoloJob.JOB_ID })
        RelayStore(context).config = RelayConfig.create()
        SoloJob.schedule(context)
        val job = js.allPendingJobs.firstOrNull { it.id == SoloJob.JOB_ID }
        if (BuildConfig.TV) assertNull("never on the TV", job)
        else {
            assertNotNull(job); assertEquals(SoloJob.EVERY_MS, job!!.intervalMillis); assertTrue(job.isPersisted)
        }
        RelayStore(context).config = null
    }

    @Test
    fun theRelayStoreKeepsTheModeOfTheLastDaysOnly() {
        val s = RelayStore(context, "relay-mode")
        s.setMode("2026-11-26", "bandle", "solo")
        s.setMode("2026-11-27", "wordle", "together")
        assertEquals("together", s.mode("2026-11-27", "wordle"))
        assertNull("yesterday's is let go", s.mode("2026-11-26", "bandle"))
    }
}
