package app.couch.alhasapa.couches

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.R
import app.couch.alhasapa.TestCouch
import app.couch.alhasapa.data.CouchRef
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.data.Person
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.data.Solo
import app.couch.alhasapa.data.SoloSide
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.data.StateStore
import app.couch.alhasapa.remote.CouchSetup
import app.couch.alhasapa.remote.CouchStore
import app.couch.alhasapa.remote.GuestSolo
import app.couch.alhasapa.remote.KeyClient
import app.couch.alhasapa.remote.KeyLink
import app.couch.alhasapa.remote.KeyLink.Msg
import app.couch.alhasapa.remote.KeyServer
import app.couch.alhasapa.remote.Links
import app.couch.alhasapa.remote.RelayConfig
import app.couch.alhasapa.remote.RelayHttp
import app.couch.alhasapa.remote.RelayStore
import app.couch.alhasapa.remote.RemoteStore
import app.couch.alhasapa.remote.RepoCoupleBook
import app.couch.alhasapa.remote.SoloRelay
import app.couch.alhasapa.results.GuestPlay
import app.couch.alhasapa.results.ResultInput
import app.couch.alhasapa.results.ResultStore
import app.couch.alhasapa.results.SoloPlay
import app.couch.alhasapa.results.SoloTargets
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * An evening at a friend's, from home and back (2.0), with the app's own pieces: the phone's home state, its stores, its one
 * connection, and two TVs of two homes on this machine's network. At every step: what the phone says, which couch got the result,
 * and which couch's phones are told.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class CouchesFlowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app get() = context as CouchApp
    private val today: String get() = CouchState.dayKey(java.time.LocalDate.now())
    private val yesterday: String get() = CouchState.dayKey(java.time.LocalDate.now().minusDays(1))
    private val pieces by lazy { PiecesConfig.load(context) }
    private val danny = CouchRef("c1111111111111aa", "הספה של דני")

    private class Memory : StateStore {
        @Volatile var text: String? = null
        override fun read() = text
        override fun write(json: String) { text = json }
    }

    /** The relay, as one server with every home's topic on it: what was said on each, in order. */
    private class Relays : RelayHttp {
        val said = CopyOnWriteArrayList<Pair<String, String>>()
        override fun post(url: String, body: String): Int { said += url.substringAfterLast('/') to body; return 200 }
        override fun get(url: String): String? {
            val topic = url.substringBefore("/json").substringAfterLast('/')
            return said.withIndex().filter { it.value.first == topic }
                .joinToString("\n") { JSONObject().put("id", "m${it.index}").put("time", it.index).put("event", "message").put("message", it.value.second).toString() }
        }
        fun on(c: RelayConfig): Int = said.count { it.first == c.topic }
    }

    private val relays = Relays()

    private inner class Tv(val name: String, couch: CouchRef, a: String, b: String) {
        val repo = StateRepository(Memory()).also { r ->
            r.update { s -> Couple.put(s, Person("a", a, "", "", 100)); Couple.put(s, Person("b", b, "", "", 100)); Couches.set(s, couch.id, couch.name.takeIf { it != TestCouch.NAME }.orEmpty(), 100) }
        }
        val store = RemoteStore(context, "tv-$name")
        val typed = CopyOnWriteArrayList<Msg>()
        val relay = RelayConfig.create()
        val relayStore = RelayStore(context, "relay-tv-$name").also { it.config = relay }
        val server = KeyServer(
            context, store = store, onInput = { typed += it }, preferredPort = 0, advertise = false, tvName = name, onLogins = { null },
            onLinked = {}, frameSource = { null }, onMirroring = {}, book = RepoCoupleBook { repo }, relay = { relay },
            // As the app does it: the couch's own phones hear of a guest's game through the couch's own relay.
            onGuestSide = { who -> SoloRelay(context, repo, relays, store = relayStore).send(who) },
        )
        val id: String get() = store.tvId
        val address: String get() = "127.0.0.1:${server.port}"
    }

    private lateinit var homeTv: Tv
    private lateinit var dannyTv: Tv
    private val couches by lazy { CouchStore(context) }
    private val remote by lazy { RemoteStore(context) }
    private val guest by lazy { GuestSolo(context) }
    /** The home couch's relay: the one its TV made and handed to the home's phones. */
    private val homeRelay get() = homeTv.relay

    @Before
    fun atHome() {
        Links.reset()
        TestCouch.ours()
        homeTv = Tv("OurTV", CouchRef(TestCouch.ID, TestCouch.NAME), "סטיב", "רוני").also { it.server.start() }
        dannyTv = Tv("DannyTV", danny, "דני", "מיה").also { it.server.start() }
        // The phone as it is after the update from 1.12: held by Steve, paired with the home's TV, with the home's relay.
        remote.me = "a"
        val token = RemoteStore.randomHex(32)
        homeTv.store.addPhone(token, "Pixel 8")
        remote.saveToken(homeTv.id, token)
        couches.homeTvs = setOf(homeTv.id)
        RelayStore(context).config = homeRelay
        // Both TVs are on this network, whichever the phone means.
        Links.factory = { ctx, target -> KeyClient(ctx, target = target, finder = { _, _ -> listOf(dannyTv.address, homeTv.address) }) }
    }

    @After
    fun close() {
        Links.reset()
        homeTv.server.stop(); dannyTv.server.stop()
        RelayStore(context).config = null
        remote.me = null
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ok()) return
            Thread.sleep(20)
        }
        fail("timed out waiting for: $what")
    }

    private fun settle(ms: Long = 300) { val until = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) } }

    private fun game(id: String) = app.catalog.game(id)!!
    private fun typed(n: Int) = ResultInput(text = "", manual = n.toString(), fail = false, credit = null)
    private val client get() = Links.client(context)
    private fun connectedTo(tv: Tv) = (client.state.value as? KeyClient.State.Connected)?.tv == tv.name

    /** The phone becomes a guest at Danny's: the TV is chosen by hand, its code typed, and the holder says who they are there. */
    private fun visitDanny(kept: Boolean, owner: Any): Person {
        Links.acquire(context, owner)
        Links.choose(context, dannyTv.id, dannyTv.address, if (kept) KeyLink.AS_GUEST else KeyLink.AS_EVENING)
        waitFor("Danny's TV shows its code") { client.state.value is KeyClient.State.NeedCode && dannyTv.server.ui.value.code != null }
        client.sendCode(dannyTv.server.ui.value.code!!)
        waitFor("let in as a guest") { connectedTo(dannyTv) }
        waitFor("Danny's players are named") { couches.people(danny.id).size >= 2 }
        val me = Person(Couple.newId(), "סטיב", "", "", System.currentTimeMillis(), guest = !kept, day = if (kept) null else Couple.evening())
        CouchSetup.startVisit(context, danny, dannyTv.id, me, kept, added = true)
        client.sendGuest(me)
        Links.unchoose(context)
        waitFor("Danny's TV names the guest among its players") { dannyTv.repo.peek { s -> Couple.all(s).any { it.who == me.who } } }
        return me
    }

    @Test
    fun anEveningAtDannysFromHomeAndBack() {
        val screen = Any()
        // ---- at home ----
        assertEquals("the connection goes to the home's TV", KeyClient.Target(setOf(homeTv.id), KeyLink.AS_HOME), Links.targetFor(context))
        assertTrue("a game played alone is the home's, and nothing is asked", SoloTargets.resolve(context, app.repo, today, "bandle") is SoloTargets.Need.Go)
        Links.acquire(context, screen)
        waitFor("connected to the home's TV, though Danny's is found first") { connectedTo(homeTv) }
        assertTrue(dannyTv.server.ui.value.phones.isEmpty() && dannyTv.server.ui.value.code == null)
        Links.release(screen)
        waitFor("let go") { homeTv.server.ui.value.phones.isEmpty() }

        // ---- a guest at Danny's ----
        val me = visitDanny(kept = false, owner = screen)
        val visit = couches.visitNow()!!
        assertEquals("מתארח בספה של דני", context.getString(R.string.visit_bar, Couches.inCouch(visit.name)))
        assertEquals("the home is the home", TestCouch.ID, Couches.of(app.repo.snapshot())!!.id)
        assertEquals(setOf(homeTv.id), couches.homeTvs)
        assertEquals("the connection is the visit's", setOf(dannyTv.id), Links.targetFor(context)!!.tvs)
        assertTrue("and stays open, with no second hello", connectedTo(dannyTv))
        assertTrue(homeTv.server.ui.value.phones.isEmpty())
        assertNotEquals("the same name in two homes is two players", "a", me.who)

        // ---- a game on Danny's TV: saved at Danny's couch, whoever holds the remote ----
        val st = client.state.value as KeyClient.State.Connected
        assertEquals("🏁 המשחק נגמר: שמירת תוצאה בספה של דני", context.getString(R.string.kb_save_result_in, Couches.inCouch(st.couch!!.name)))
        client.send(Msg.SaveResult)
        waitFor("the press reached Danny's TV") { dannyTv.typed.any { it == Msg.SaveResult } }
        ResultStore.save(dannyTv.repo, game("wordle"), today, null, typed(3))      // what Danny's TV does with it
        assertNotNull(CouchState.entry(dannyTv.repo.snapshot(), today, "wordle"))
        assertNull("not on the guest's phone", CouchState.entry(app.repo.snapshot(), today, "wordle"))
        assertNull("nor on the guest's home TV", CouchState.entry(homeTv.repo.snapshot(), today, "wordle"))
        assertTrue("nothing was pressed on the home's TV", homeTv.typed.none { it == Msg.SaveResult || it is Msg.Text })

        // ---- a game played alone, for home: asked before it opens ----
        val ask = SoloTargets.resolve(context, app.repo, today, "bandle") as SoloTargets.Need.Ask
        assertEquals("הספה של סטיב ורוני · הבית שלי", context.getString(R.string.target_home, ask.home.name))
        assertEquals("הספה של דני · מתארח כאן", context.getString(R.string.target_visit, ask.visit.name))
        assertEquals("לאיזו ספה לשייך את התוצאה שלך ב־Bandle?", context.getString(R.string.target_question, game("bandle").n))
        SoloTargets.choose(context, today, "bandle", ask.home)
        assertEquals("the answer stands for that game", SoloTargets.Need.Go(ask.home), SoloTargets.resolve(context, app.repo, today, "bandle"))
        assertEquals("שמירת תוצאת הסולו בספה של סטיב ורוני", context.getString(R.string.solo_save_in, Couches.inCouch(ask.home.name)))
        SoloPlay.save(app.repo, game("bandle"), today, ask.home.who, typed(2))
        SoloRelay(context, app.repo, relays).send(ask.home.who)
        assertEquals(2.0, Solo.side(app.repo.snapshot(), today, "bandle", "a")!!.v!!, 0.0)
        assertEquals("said on the home's relay", 1, relays.on(homeRelay))
        assertEquals("and nowhere else", 0, relays.on(dannyTv.relay))
        settle()
        assertTrue("Danny's couch has nothing of it", Solo.sides(dannyTv.repo.snapshot(), today, "bandle").isEmpty())
        // Roni's phone, at home, hears of it; Maya's, at Danny's, does not.
        val roni = StateRepository(Memory()).also { r -> r.update { TestCouch.ours(it) } }
        val roniNews = SoloRelay(context, roni, relays, store = RelayStore(context, "relay-roni").also { it.config = homeRelay }).receive("b")!!
        assertEquals(listOf("bandle" to "a"), roniNews.map { it.game to it.who })
        val maya = StateRepository(Memory()).also { r -> r.update { s -> Couches.set(s, danny.id, danny.name, 1) } }
        val mayaRelay = SoloRelay(context, maya, relays, store = RelayStore(context, "relay-maya").also { it.config = dannyTv.relay })
        assertTrue(mayaRelay.receive("b")!!.isEmpty())

        // ---- another game played alone, for Danny's couch ----
        val ask2 = SoloTargets.resolve(context, app.repo, today, "connections") as SoloTargets.Need.Ask
        SoloTargets.choose(context, today, "connections", ask2.visit)
        val t = (SoloTargets.resolve(context, app.repo, today, "connections") as SoloTargets.Need.Go).t
        assertFalse(SoloTargets.isHome(t, app.repo))
        assertEquals("שמירת תוצאת הסולו בספה של דני", context.getString(R.string.solo_save_in, Couches.inCouch(t.name)))
        val homeBefore = app.repo.json()
        GuestPlay.save(context, game("connections"), today, t, typed(1))
        waitFor("Danny's TV keeps the guest's side") { guest.entry(danny.id, today, "connections")?.sent == true }
        assertEquals(1.0, Solo.side(dannyTv.repo.snapshot(), today, "connections", me.who)!!.v!!, 0.0)
        assertEquals("the home's state is untouched: no history, no points, no side", homeBefore, app.repo.json())
        assertEquals("nothing more on the home's relay", 1, relays.on(homeRelay))
        waitFor("Danny's own phones are told, through Danny's relay") { relays.on(dannyTv.relay) == 1 }
        assertEquals(listOf("connections" to me.who), mayaRelay.receive("b")!!.map { it.game to it.who })
        assertTrue("and Roni hears nothing of it", SoloRelay(context, roni, relays, store = RelayStore(context, "relay-roni")).receive("b")!!.isEmpty())
        assertTrue("no points for the game on Danny's TV from it", CouchState.entry(dannyTv.repo.snapshot(), today, "connections") == null)
        val card = GuestPlay.outcome(context, game("connections"), today, t, pieces)!!
        assertEquals("הספה של דני", card.couch)
        assertFalse(card.pending)

        // ---- the connection drops in the middle of a save ----
        Links.release(screen)
        waitFor("Danny's TV lost the phone") { dannyTv.server.ui.value.phones.isEmpty() }
        val ask3 = SoloTargets.resolve(context, app.repo, today, "strands") as SoloTargets.Need.Ask
        SoloTargets.choose(context, today, "strands", ask3.visit)
        GuestPlay.save(context, game("strands"), today, ask3.visit, typed(4))
        val waiting = GuestPlay.outcome(context, game("strands"), today, ask3.visit, pieces)!!
        assertTrue("kept on the phone, to be sent", waiting.pending && guest.pending(danny.id).size == 1)
        assertEquals("the button still says where it goes", "שמירת תוצאת הסולו בספה של דני",
            context.getString(R.string.solo_save_in, Couches.inCouch((SoloTargets.resolve(context, app.repo, today, "strands") as SoloTargets.Need.Go).t.name)))
        assertEquals("and it is counted nowhere meanwhile", homeBefore, app.repo.json())
        assertTrue(Solo.sides(dannyTv.repo.snapshot(), today, "strands").isEmpty())
        // The app is opened again: the visit is still on, the connection comes back, and the result goes, once.
        Links.acquire(context, screen)
        waitFor("sent when the TV is reached again, with no code") { guest.entry(danny.id, today, "strands")?.sent == true }
        client.flushGuest(); client.send(Msg.SoloSideMsg(today, "strands", guest.entry(danny.id, today, "strands")!!.side.toJson()))
        settle()
        assertEquals("sent again, counted once", 1, Solo.sides(dannyTv.repo.snapshot(), today, "strands").count { it.who == me.who })

        // ---- the visit ends ----
        CouchSetup.endVisit(context)
        Links.refresh(context)
        waitFor("Danny's TV is let go of at once") { dannyTv.server.ui.value.phones.isEmpty() }
        waitFor("and the phone is at home again") { connectedTo(homeTv) }
        assertNull(couches.visitNow())
        assertEquals("a couch that was for the evening is forgotten, its pairing too", "", remote.tokenFor(dannyTv.id))
        assertTrue(couches.saved().isEmpty())
        assertEquals("the result the guest saved stays with the host", 1.0, Solo.side(dannyTv.repo.snapshot(), today, "connections", me.who)!!.v!!, 0.0)
        assertEquals("a game already said to be Danny's is Danny's still", danny.id, (SoloTargets.resolve(context, app.repo, today, "connections") as SoloTargets.Need.Go).t.couch)
        assertTrue("a new game is the home's, and nothing is asked", SoloTargets.isHome((SoloTargets.resolve(context, app.repo, today, "tiles") as SoloTargets.Need.Go).t, app.repo))
        Links.release(screen)

        // ---- the next day: a game played yesterday on the computer ----
        assertNull("the visit does not begin again by itself", couches.visitNow(CouchState.dayKey(java.time.LocalDate.now().plusDays(1))))
        val late = SoloTargets.resolve(context, app.repo, yesterday, "pips") as SoloTargets.Need.Go
        assertTrue("with no visit on, it is the home's, whatever day the puzzle was", SoloTargets.isHome(late.t, app.repo))
        assertEquals("שחקן: סטיב · הספה של סטיב ורוני · החידה של אתמול",
            context.getString(R.string.solo_who_where, late.t.whoName, late.t.name, context.getString(R.string.day_yesterday)))
        SoloPlay.save(app.repo, game("pips"), yesterday, late.t.who, typed(5))
        assertNotNull(Solo.side(app.repo.snapshot(), yesterday, "pips", "a"))
        assertTrue(Solo.sides(dannyTv.repo.snapshot(), yesterday, "pips").isEmpty())
    }

    @Test
    fun aSavedCouchIsVisitedAgainWithoutSettingUpAndTheHomeNeverChangesByItself() {
        val screen = Any()
        val me = visitDanny(kept = true, owner = screen)
        assertEquals(KeyLink.AS_GUEST, Links.targetFor(context)!!.role)
        CouchSetup.endVisit(context)
        Links.refresh(context)
        waitFor("at home again") { connectedTo(homeTv) }
        // Kept: its name and id, the TV it was met on, and who the holder is there.
        val saved = couches.saved(danny.id)!!
        assertEquals(danny.name, saved.name)
        assertEquals(me.who to "סטיב", saved.me to saved.meName)
        assertEquals("the home did not change", TestCouch.ID, Couches.of(app.repo.snapshot())!!.id)
        assertEquals(setOf(homeTv.id), couches.homeTvs)

        // The next visit: chosen from the list, no code, the same player.
        Links.choose(context, dannyTv.id, null, KeyLink.AS_GUEST)
        waitFor("let in with no code") { connectedTo(dannyTv) }
        assertNull(dannyTv.server.ui.value.code)
        val again = couches.people(danny.id).first { it.who == saved.me }
        CouchSetup.startVisit(context, danny, dannyTv.id, again, kept = true)
        Links.unchoose(context)
        assertEquals(me.who, couches.visitNow()!!.me)
        waitFor("Danny's TV knows who they are") { dannyTv.server.ui.value.phones.contains("סטיב") }

        // Joining a contest there needs no question: the couch is said by the press.
        dannyTv.repo.update { s -> Solo.put(s, today, "connections", SoloSide("a", 2.0, t = 1_000, kind = "mistakes")) }
        dannyTv.server.stateChanged()
        waitFor("the contest is offered") { guest.contests(danny.id, today).contains("connections") }
        assertEquals("להצטרף לתחרות Connections בספה של דני", context.getString(R.string.visit_join, game("connections").n, Couches.inCouch(danny.name)))
        SoloTargets.choose(context, today, "connections", SoloTargets.visit(context)!!)
        assertEquals("not asked again", danny.id, (SoloTargets.resolve(context, app.repo, today, "connections") as SoloTargets.Need.Go).t.couch)
        val t = SoloTargets.visit(context)!!
        GuestPlay.markPlayed(context, game("connections"), today, t)
        waitFor("joined") { Solo.players(dannyTv.repo.snapshot(), today, "connections").size == 2 }
        // Leaving it without a result: Danny is not kept waiting.
        GuestPlay.withdraw(context, game("connections"), today, t)
        waitFor("left") { Solo.players(dannyTv.repo.snapshot(), today, "connections").size == 1 }
        CouchSetup.endVisit(context)
        Links.release(screen)
    }

    @Test
    fun makingDannysCouchTheHomeIsAskedForInSoManyWordsAndMovesNothing() {
        val screen = Any()
        app.repo.update { s -> CouchState.setEntry(s, "2026-11-20", "wordle", JSONObject().put("w", "a").put("t", 5L)) }
        val oldHome = app.repo.json()
        assertEquals("להגדיר את הספה של דני כספת הבית שלך?", context.getString(R.string.couch_set_home_title, danny.name))
        assertTrue(context.getString(R.string.couch_set_home_body, Couches.inCouch(TestCouch.NAME)).contains("התוצאות הישנות נשארות בספה של סטיב ורוני"))
        CouchSetup.setHome(context, app.repo, danny, listOf(dannyTv.id))
        assertEquals(danny.id, Couches.of(app.repo.snapshot())!!.id)
        assertNull("nothing of the old home is in the new one", CouchState.entry(app.repo.snapshot(), "2026-11-20", "wordle"))
        // Let in as one of Danny's own, with the code on Danny's TV: its players and its history come.
        dannyTv.repo.update { s -> CouchState.setEntry(s, "2026-11-21", "bandle", JSONObject().put("w", "b").put("t", 6L)) }
        Links.acquire(context, screen)
        Links.choose(context, dannyTv.id, dannyTv.address, KeyLink.AS_HOME)
        waitFor("Danny's TV shows a code, for one of the home's own") { dannyTv.server.ui.value.codeAs == KeyLink.AS_HOME && client.state.value is KeyClient.State.NeedCode }
        client.sendCode(dannyTv.server.ui.value.code!!)
        waitFor("the new home's history") { CouchState.entry(app.repo.snapshot(), "2026-11-21", "bandle") != null }
        assertEquals("דני" to "מיה", CouchState.names(app.repo.snapshot()))
        assertTrue("new games played alone are the new home's", SoloTargets.resolve(context, app.repo, today, "tiles") == SoloTargets.Need.Who)
        assertNull("the old home's TV has nothing of the new one", CouchState.entry(homeTv.repo.snapshot(), "2026-11-21", "bandle"))
        // The old home is kept whole among the saved couches; Danny's TV got nothing of it.
        assertTrue(couches.saved(TestCouch.ID)!!.archived)
        assertNull(CouchState.entry(dannyTv.repo.snapshot(), "2026-11-20", "wordle"))
        Links.unchoose(context); Links.release(screen)
        waitFor("let go") { dannyTv.server.ui.value.phones.isEmpty() }
        // And back, as it was.
        CouchSetup.setHome(context, app.repo, CouchRef(TestCouch.ID, TestCouch.NAME), listOf(homeTv.id))
        assertEquals(JSONObject(oldHome).toString(), app.repo.snapshot().toString())
    }
}
