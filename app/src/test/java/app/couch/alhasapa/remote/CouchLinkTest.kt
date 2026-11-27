package app.couch.alhasapa.remote

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.data.CouchRef
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.data.Note
import app.couch.alhasapa.data.Notes
import app.couch.alhasapa.data.Person
import app.couch.alhasapa.data.Solo
import app.couch.alhasapa.data.SoloSide
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.data.StateStore
import app.couch.alhasapa.remote.KeyLink.Msg
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Couches on the link (2.0): TVs and phones of different homes, each with its own state and its own stores, talking over real
 * sockets on this machine. A phone comes to a TV as one of the home's own or as a guest; a guest gets the remote, the players' names
 * and the contests, and nothing of the home's history, its relay or anyone's logins; and a phone connects only to the TV it means.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class CouchLinkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val today: String get() = CouchState.dayKey(java.time.LocalDate.now())
    @Volatile private var evening = "2026-11-27"

    private class Memory : StateStore {
        @Volatile var text: String? = null
        override fun read() = text
        override fun write(json: String) { text = json }
    }

    private fun repo(couch: CouchRef?, a: String = "דני", b: String = "מיה"): StateRepository = StateRepository(Memory()).also { r ->
        if (couch != null) r.update { s ->
            Couple.put(s, Person("a", a, "", "", 100)); Couple.put(s, Person("b", b, "", "", 100))
            Couches.set(s, couch.id, couch.name, 100)
        }
    }

    /** A TV of a home: its state, what it keeps of the phones, and what was typed on it, handed to it, said through its relay. */
    private inner class Tv(val name: String, couch: CouchRef?, a: String = "דני", b: String = "מיה") {
        val repo = repo(couch, a, b)
        val store = RemoteStore(context, "tv-$name")
        val typed = CopyOnWriteArrayList<Msg>()
        val logins = CopyOnWriteArrayList<String>()
        val toldRelay = CopyOnWriteArrayList<String>()
        val relayTaken = CopyOnWriteArrayList<RelayConfig>()
        val relay = RelayConfig.create()
        val server = KeyServer(
            context, store = store, onInput = { typed += it }, preferredPort = 0, advertise = false, tvName = name,
            onLogins = { payload -> logins += payload; "NYT Games" to 1 }, onLinked = {}, frameSource = { null }, onMirroring = {},
            book = RepoCoupleBook { repo }, relay = { relay }, onGuestSide = { toldRelay += it }, evening = { evening },
            onRelay = { c -> relayTaken += c; true },
        )
        val id: String get() = store.tvId
        val address: String get() = "127.0.0.1:${server.port}"
        fun start() = apply { server.start(); tvs += this }
    }

    /** A phone: its home's state, its pairings, what it keeps of friends' couches, and the games played alone at them. */
    private inner class Phone(val n: Int, home: CouchRef?, a: String = "סטיב", b: String = "רוני") {
        val repo = repo(home, a, b)
        val store = RemoteStore(context, "phone-$n")
        val couches = CouchStore(context, "couches-$n")
        val guest = GuestSolo(context, "guest-$n")
        fun client(target: KeyClient.Target?, vararg found: Tv): KeyClient = KeyClient(
            context, store = store, phoneName = "Pixel $n", book = RepoCoupleBook { repo }, target = target, couches = couches, guestSolo = guest,
            finder = { _, _ -> found.map { it.address } },
        ).also { clients += it }
    }

    private val tvs = ArrayList<Tv>()
    private val clients = ArrayList<KeyClient>()

    @After fun close() { clients.forEach { it.stop() }; tvs.forEach { it.server.stop() }; RelayStore(context).config = null }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ok()) return
            Thread.sleep(20)
        }
        fail("timed out waiting for: $what")
    }

    private fun settle(ms: Long = 400) { val until = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) } }

    private val danny = CouchRef("c1111111111111aa", "הספה של דני")
    private val ours = CouchRef("c2222222222222bb", "הספה של סטיב ורוני")

    /** [phone] comes to [tv] as [role], with the code the TV shows. */
    private fun pair(phone: Phone, tv: Tv, role: String): KeyClient {
        val c = phone.client(KeyClient.Target(setOf(tv.id), role, tv.address), tv)
        c.start()
        waitFor("${tv.name} shows a code") { c.state.value is KeyClient.State.NeedCode && tv.server.ui.value.code != null }
        assertEquals("the TV says what the phone asks to come as", role, tv.server.ui.value.codeAs)
        c.sendCode(tv.server.ui.value.code!!)
        waitFor("let in") { c.state.value is KeyClient.State.Connected }
        return c
    }

    /** As a guest: who the phone's holder is there, added by this phone (a guest of the evening). */
    private fun arrive(phone: Phone, tv: Tv, c: KeyClient, name: String, kept: Boolean = false): Person {
        val me = Person(Couple.newId(), name, "", "", System.currentTimeMillis(), guest = !kept, day = if (kept) null else evening)
        val couch = (c.state.value as KeyClient.State.Connected).couch!!
        phone.couches.save(SavedCouch(couch.id, couch.name, listOf(tv.id), me.who, me.name, kept = kept, made = me))
        phone.couches.visit = Visit(couch.id, couch.name, tv.id, me.who, me.name, evening, 1)
        c.sendGuest(me)
        waitFor("${tv.name} names $name among its players") { tv.repo.peek { s -> Couple.all(s).any { it.who == me.who } } }
        return me
    }

    private fun hand(phone: Phone, c: KeyClient, couch: CouchRef, me: Person, game: String, side: SoloSide) {
        phone.guest.put(GuestEntry(couch.id, couch.name, me.who, me.name, today, game, side.copy(who = me.who), sent = false))
        c.flushGuest()
    }

    @Test
    fun aGuestGetsTheRemoteThePlayersAndNothingOfTheHome() {
        val tv = Tv("MiTV", danny).start()
        tv.repo.update { s -> CouchState.setEntry(s, today, "wordle", JSONObject().put("w", "a").put("r", JSONObject().put("v", 3)).put("t", 50L)) }
        val phone = Phone(1, ours)
        phone.repo.update { s -> CouchState.setEntry(s, today, "bandle", JSONObject().put("w", "b").put("t", 60L)) }
        val before = phone.repo.json()
        RelayStore(context).config = null

        val c = pair(phone, tv, KeyLink.AS_EVENING)
        val st = c.state.value as KeyClient.State.Connected
        assertEquals("the phone knows whose couch this is", danny, st.couch)
        assertEquals(KeyLink.AS_EVENING, st.role)
        waitFor("the players, to choose who the holder is there") { phone.couches.people(danny.id).map { it.name }.containsAll(listOf("דני", "מיה")) }
        assertEquals("the TV lists it among its guests", listOf("Pixel 1"), tv.server.ui.value.guests)

        // The remote works.
        c.send(Msg.Text("hi"))
        waitFor("typed on the TV") { tv.typed.any { it is Msg.Text } }

        // Nothing of the home's state comes, nothing of the phone's goes; no relay; no login.
        phone.repo.update { s -> CouchState.setEntry(s, today, "strands", JSONObject().put("w", "a").put("t", 70L)) }
        ShareBus.changed()
        tv.repo.update { s -> CouchState.setEntry(s, today, "connections", JSONObject().put("w", "b").put("t", 80L)) }
        c.send(Msg.Share(app.couch.alhasapa.data.Shared.share(phone.repo.snapshot(), listOf(today))))
        c.sendLogins("nyt", "{}")
        settle()
        assertNull("Danny's evening is not on the guest's phone", CouchState.entry(phone.repo.snapshot(), today, "wordle"))
        assertNull(CouchState.entry(phone.repo.snapshot(), today, "connections"))
        assertNull("the guest's evening is not on Danny's TV", CouchState.entry(tv.repo.snapshot(), today, "bandle"))
        assertNull(CouchState.entry(tv.repo.snapshot(), today, "strands"))
        assertEquals("Danny's players are not players of the guest's home", listOf("סטיב", "רוני"), Couple.all(phone.repo.snapshot()).map { it.name })
        assertEquals(ours, Couches.of(phone.repo.snapshot()))
        assertNull("the home's relay is not handed to a guest", RelayStore(context).config)
        assertEquals("a login is not sent to a friend's TV", "guest", (c.handoff.value as KeyClient.Handoff.Failed).why)
        assertTrue(tv.logins.isEmpty())
        assertTrue("nothing else of the phone's state changed", before.length <= phone.repo.json().length)
    }

    @Test
    fun aGuestHandsInAGamePlayedAloneAndTheEnvelopesOpenForThem() {
        val tv = Tv("MiTV", danny).start()
        val phone = Phone(1, ours)
        val c = pair(phone, tv, KeyLink.AS_EVENING)
        val me = arrive(phone, tv, c, "סטיב")
        // Danny handed in; Maya joined and is still playing.
        tv.repo.update { s ->
            Solo.put(s, today, "connections", SoloSide("a", 2.0, t = 1_000, kind = "mistakes"))
            Solo.put(s, today, "connections", SoloSide("b", null, t = 1_100))
        }
        tv.server.stateChanged()
        waitFor("the contest as a guest may see it") { phone.guest.contest(danny.id, today, "connections").size == 2 }
        val sealed = phone.guest.contest(danny.id, today, "connections").associateBy { it.who }
        assertTrue("Danny's result is in its envelope", sealed["a"]!!.env && sealed["a"]!!.v == null)
        assertFalse("Maya has none yet", sealed["b"]!!.env)

        // The guest joins (played, no result yet), then hands in.
        hand(phone, c, danny, me, "connections", SoloSide(me.who, null, t = 2_000))
        waitFor("the TV keeps it, and says so") { phone.guest.entry(danny.id, today, "connections")?.sent == true }
        assertEquals(3, Solo.players(tv.repo.snapshot(), today, "connections").size)
        assertTrue("still sealed for someone with no result", phone.guest.contest(danny.id, today, "connections").first { it.who == "a" }.env)
        hand(phone, c, danny, me, "connections", SoloSide(me.who, 1.0, t = 3_000, kind = "mistakes"))
        waitFor("the envelopes open for the guest") { phone.guest.contest(danny.id, today, "connections").firstOrNull { it.who == "a" }?.v == 2.0 }
        assertEquals(1.0, Solo.side(tv.repo.snapshot(), today, "connections", me.who)!!.v!!, 0.0)
        assertNull("nobody is crowned while Maya plays", Solo.winner(tv.repo.snapshot(), today, "connections", "mistakes"))
        // Danny's own phones hear of it through Danny's relay; the guest's home hears nothing.
        assertTrue(tv.toldRelay.contains(me.who))
        assertNull("not in the guest's home state", phone.repo.snapshot().optJSONObject("solo"))

        // Sent again (a lost answer, a second press): counted once.
        val side = phone.guest.entry(danny.id, today, "connections")!!.side
        c.send(Msg.SoloSideMsg(today, "connections", side.toJson()))
        c.send(Msg.SoloSideMsg(today, "connections", side.toJson()))
        settle()
        assertEquals(1, Solo.sides(tv.repo.snapshot(), today, "connections").count { it.who == me.who })
        assertEquals(3_000L, Solo.side(tv.repo.snapshot(), today, "connections", me.who)!!.t)
        // A guest writes only their own side, whatever the message says.
        c.send(Msg.SoloSideMsg(today, "connections", SoloSide("a", 0.0, t = 9_000, kind = "mistakes").toJson().put("who", "a")))
        settle()
        assertEquals("Danny's own side is untouched", 2.0, Solo.side(tv.repo.snapshot(), today, "connections", "a")!!.v!!, 0.0)

        // Maya hands in: the crown, on the TV and on the guest's phone.
        tv.repo.update { s -> Solo.put(s, today, "connections", SoloSide("b", 3.0, t = 4_000, kind = "mistakes")) }
        tv.server.stateChanged()
        waitFor("Maya's result on the guest's phone") { phone.guest.contest(danny.id, today, "connections").firstOrNull { it.who == "b" }?.v == 3.0 }
        assertEquals(me.who, Solo.winner(tv.repo.snapshot(), today, "connections", "mistakes"))
    }

    @Test
    fun threeFriendsJoinDannysContestAndTheFourthPlaysForHisOwnHome() {
        val tv = Tv("MiTV", danny).start()
        val phones = (1..4).map { Phone(it, CouchRef("c33333333333330$it", "הספה של חבר $it"), "חבר $it", "בת זוג $it") }
        val links = phones.map { pair(it, tv, KeyLink.AS_EVENING) }
        // Two of them have the same name: they are two players all the same.
        val names = listOf("נועה", "נועה", "יואב", "עידו")
        val who = phones.indices.map { arrive(phones[it], tv, links[it], names[it]) }
        assertEquals("four guests, four players", 4, who.map { it.who }.toSet().size)

        // Three open Connections for Danny's contest; the fourth plays it for his own home (nothing of it goes to Danny's TV).
        for (i in 0..2) hand(phones[i], links[i], danny, who[i], "connections", SoloSide(who[i].who, null, t = 1_000L + i))
        phones[3].repo.update { s -> Solo.put(s, today, "connections", SoloSide("a", 0.0, t = 1_500, kind = "mistakes")) }
        waitFor("three joined at Danny's") { Solo.players(tv.repo.snapshot(), today, "connections").size == 3 }
        for (i in 0..2) waitFor("each sees who joined") { phones[i].guest.contest(danny.id, today, "connections").size == 3 }
        assertFalse("the fourth is not in it", Solo.sides(tv.repo.snapshot(), today, "connections").any { it.who == who[3].who })

        // They hand in one by one; whoever has not sees envelopes, not results.
        hand(phones[0], links[0], danny, who[0], "connections", SoloSide(who[0].who, 2.0, t = 2_000, kind = "mistakes"))
        waitFor("the second sees that the first handed in, not what") {
            phones[1].guest.contest(danny.id, today, "connections").firstOrNull { it.who == who[0].who }?.env == true
        }
        assertNull(phones[1].guest.contest(danny.id, today, "connections").first { it.who == who[0].who }.v)
        hand(phones[1], links[1], danny, who[1], "connections", SoloSide(who[1].who, 2.0, t = 2_100, kind = "mistakes"))
        waitFor("the second handed in: the first's result is open to them") {
            phones[1].guest.contest(danny.id, today, "connections").firstOrNull { it.who == who[0].who }?.v == 2.0
        }
        assertNull("nobody is crowned while the third plays", Solo.winner(tv.repo.snapshot(), today, "connections", "mistakes"))
        assertNull("nor is anything shown on the TV, where the whole room looks", app.couch.alhasapa.results.SoloPlay.tvReveal(
            tv.repo.snapshot(), listOf((context as CouchApp).catalog.game("connections")!!), today, app.couch.alhasapa.data.PiecesConfig.load(context),
        ) { false })
        hand(phones[2], links[2], danny, who[2], "connections", SoloSide(who[2].who, 4.0, t = 2_200, kind = "mistakes"))
        // The three handed in: the reveal, without waiting for the fourth. The two of the same name are level at the top.
        waitFor("the contest is decided") { Solo.winner(tv.repo.snapshot(), today, "connections", "mistakes") != null }
        assertEquals("t", Solo.winner(tv.repo.snapshot(), today, "connections", "mistakes"))
        assertEquals(setOf(who[0].who, who[1].who), Solo.leadersOf(Solo.sides(tv.repo.snapshot(), today, "connections"), "mistakes").toSet())
        val reveal = app.couch.alhasapa.results.SoloPlay.tvReveal(
            tv.repo.snapshot(), listOf((context as CouchApp).catalog.game("connections")!!), today, app.couch.alhasapa.data.PiecesConfig.load(context),
        ) { false }!!.second
        assertEquals("three separate players on the TV", 3, 1 + reveal.others.size)
        assertEquals(listOf("נועה", "נועה"), reveal.level)
        assertEquals("the fourth's result is his own home's", 0.0, Solo.side(phones[3].repo.snapshot(), today, "connections", "a")!!.v!!, 0.0)
        assertEquals(setOf(who[0].who, who[1].who, who[2].who), tv.toldRelay.toSet())
    }

    @Test
    fun aPhoneConnectsOnlyToTheTvItMeansThoughAnotherIsOnTheSameNetwork() {
        val home = Tv("OurTV", ours, "סטיב", "רוני").start()
        val friend = Tv("DannyTV", danny).start()
        val phone = Phone(1, ours)
        // Paired with the home's TV before 2.0: a token, and nothing said of what it is.
        val token = RemoteStore.randomHex(32)
        home.store.addPhone(token, "Pixel 1")
        phone.store.saveToken(home.id, token)

        // No TV is meant: nothing is looked for, whatever is there.
        val c = phone.client(null, friend, home)
        c.start()
        waitFor("it says there is no TV to connect to") { c.state.value == KeyClient.State.NoTv }
        settle()
        assertTrue(home.server.ui.value.phones.isEmpty() && friend.server.ui.value.phones.isEmpty())

        // The home's TV is meant; the friend's is found first on the network, and is let go of before anything is said to it.
        c.retarget(KeyClient.Target(setOf(home.id), KeyLink.AS_HOME))
        waitFor("connected to the home's TV") { (c.state.value as? KeyClient.State.Connected)?.tv == "OurTV" }
        assertEquals(KeyLink.AS_HOME, (c.state.value as KeyClient.State.Connected).role)
        settle()
        assertNull("the friend's TV was asked nothing: it shows no code", friend.server.ui.value.code)
        assertTrue(friend.server.ui.value.phones.isEmpty())
        assertEquals("where each TV is, is remembered apart", home.address, phone.store.addressOf(home.id))
        assertNull(phone.store.addressOf(friend.id))

        // A visit to the friend's begins: the home's TV is let go of at once, and the friend's asks for its code.
        c.retarget(KeyClient.Target(setOf(friend.id), KeyLink.AS_EVENING))
        waitFor("the friend's TV shows a code for a guest of the evening") { friend.server.ui.value.codeAs == KeyLink.AS_EVENING }
        waitFor("the home's TV has no phone") { home.server.ui.value.phones.isEmpty() }
        // The visit is over.
        c.retarget(KeyClient.Target(setOf(home.id), KeyLink.AS_HOME))
        waitFor("back at home") { (c.state.value as? KeyClient.State.Connected)?.tv == "OurTV" }
        waitFor("the friend's TV stops showing the code") { friend.server.ui.value.code == null }
    }

    @Test
    fun aTvTakenForTheHomesThatIsOfAnotherCouchIsToldNothing() {
        val friend = Tv("DannyTV", danny).start()
        val phone = Phone(1, ours)
        val c = phone.client(KeyClient.Target(setOf(friend.id), KeyLink.AS_HOME, friend.address), friend)
        c.start()
        waitFor("the phone sees it is of another couch") { c.state.value is KeyClient.State.OtherCouch }
        assertEquals(danny, (c.state.value as KeyClient.State.OtherCouch).tv.couch)
        settle()
        assertNull("no hello went: no code is shown", friend.server.ui.value.code)
        assertTrue(friend.typed.isEmpty())

        // A phone from before 2.0 that knew several TVs only asks which couch each is of.
        val asking = Phone(2, null).client(KeyClient.Target(setOf(friend.id), KeyClient.Target.ASK, friend.address), friend)
        asking.start()
        waitFor("it asks the person") { asking.state.value is KeyClient.State.AskHome }
        assertEquals("DannyTV", (asking.state.value as KeyClient.State.AskHome).tv.name)
        settle()
        assertNull(friend.server.ui.value.code)
    }

    @Test
    fun aNewTvJoinsTheCouchAPhoneBeganWithItsHistoryAndItsRelay() {
        val tv = Tv("NewTV", null).start()
        assertTrue("nothing was chosen on it", Couches.needsSetup(tv.repo.snapshot()))
        val phone = Phone(1, ours)
        phone.repo.update { s -> CouchState.setEntry(s, "2026-10-01", "wordle", JSONObject().put("w", "a").put("r", JSONObject().put("v", 3)).put("t", 50L)) }
        val couchRelay = RelayConfig.create()
        RelayStore(context).config = couchRelay

        val c = pair(phone, tv, KeyLink.AS_HOME)
        assertNull("the TV said it has no couch", (c.state.value as KeyClient.State.Connected).couch)
        waitFor("the TV is of the phone's couch now") { Couches.of(tv.repo.snapshot()) == ours }
        waitFor("with its history, however old") { CouchState.entry(tv.repo.snapshot(), "2026-10-01", "wordle") != null }
        assertEquals("סטיב" to "רוני", CouchState.names(tv.repo.snapshot()))
        assertEquals("and the couch's own relay, not a new one", listOf(couchRelay), tv.relayTaken.toList())
        assertEquals("the phone is what it was", ours, Couches.of(phone.repo.snapshot()))
        assertEquals("סטיב" to "רוני", CouchState.names(phone.repo.snapshot()))
    }

    @Test
    fun aNewPhoneJoinsTheCouchOfTheHomesTvAndGetsItsPlayers() {
        val tv = Tv("OurTV", ours, "סטיב", "רוני").start()
        tv.repo.update { s ->
            CouchState.setEntry(s, "2026-10-01", "wordle", JSONObject().put("w", "a").put("t", 50L))
            Couple.put(s, Person("u1a2b3c4", "סבתא", "star", "green", 300))
        }
        val before = tv.repo.json()
        val phone = Phone(1, null)
        assertTrue(Couches.needsSetup(phone.repo.snapshot()))
        val c = pair(phone, tv, KeyLink.AS_HOME)
        waitFor("the phone is of the TV's couch") { Couches.of(phone.repo.snapshot()) == ours }
        waitFor("with its history") { CouchState.entry(phone.repo.snapshot(), "2026-10-01", "wordle") != null }
        assertEquals("its players, to be asked which of them holds the phone", listOf("סטיב", "רוני", "סבתא"), Couple.all(phone.repo.snapshot()).map { it.name })
        waitFor("nothing more is on its way") { !c.awaitingAll }
        settle()
        assertEquals("the new phone had nothing of its own to tell: the TV's state is as it was", before, tv.repo.json())
        assertEquals("the home's relay goes to one of the home's own", tv.relay, RelayStore(context).config)
    }

    @Test
    fun aPairingFromBefore20IsOfTheHomeAndAGuestNeedsTheCodeToBecomeOne() {
        val tv = Tv("OurTV", ours, "סטיב", "רוני").start()
        val phone = Phone(1, ours)
        // From before 2.0: let in as one of the home's own, with no code.
        val token = RemoteStore.randomHex(32)
        tv.store.addPhone(token, "Pixel 1")
        phone.store.saveToken(tv.id, token)
        val old = phone.client(KeyClient.Target(setOf(tv.id), KeyLink.AS_HOME, tv.address), tv)
        old.start()
        waitFor("as before") { (old.state.value as? KeyClient.State.Connected)?.role == KeyLink.AS_HOME }
        assertNull(tv.server.ui.value.code)
        old.stop()
        waitFor("gone") { tv.server.ui.value.phones.isEmpty() }

        // A friend paired as a guest who may come again: back without a code, as a guest.
        val friend = Phone(2, danny, "דני", "מיה")
        val g = pair(friend, tv, KeyLink.AS_GUEST)
        assertEquals(listOf("Pixel 2"), tv.store.pairedGuests())
        g.stop()
        waitFor("gone") { tv.server.ui.value.phones.isEmpty() }
        val again = friend.client(KeyClient.Target(setOf(tv.id), KeyLink.AS_GUEST, tv.address), tv)
        again.start()
        waitFor("back as a guest, no code") { (again.state.value as? KeyClient.State.Connected)?.role == KeyLink.AS_GUEST }
        // While their own home is another couch, asking to be of this one says nothing to the TV at all.
        again.retarget(KeyClient.Target(setOf(tv.id), KeyLink.AS_HOME, tv.address))
        waitFor("the phone sees this TV is of another couch than its home") { again.state.value is KeyClient.State.OtherCouch }
        assertNull(tv.server.ui.value.code)
        again.stop()
        waitFor("gone") { tv.server.ui.value.phones.isEmpty() }
        // They make this couch their home (their phone's state is this couch's now, with nothing in it yet): to be let in as one
        // of the home's own they need the code again, and whoever reads it out sees what is asked.
        val moved = Phone(2, null).also { it.repo.update { st -> Couches.set(st, ours.id, "", 0L) } }
        val asHome = moved.client(KeyClient.Target(setOf(tv.id), KeyLink.AS_HOME, tv.address), tv)
        asHome.start()
        waitFor("the TV asks for a code, for one of the home's own") { tv.server.ui.value.codeAs == KeyLink.AS_HOME && asHome.state.value is KeyClient.State.NeedCode }
        asHome.sendCode(tv.server.ui.value.code!!)
        waitFor("let in as one of the home's own") { (asHome.state.value as? KeyClient.State.Connected)?.role == KeyLink.AS_HOME }
        waitFor("and handed the home's players") { CouchState.names(moved.repo.snapshot()) == ("סטיב" to "רוני") }
        assertTrue("no longer among the guests", tv.store.pairedGuests().isEmpty())
        asHome.stop()
        waitFor("gone") { tv.server.ui.value.phones.isEmpty() && tv.server.ui.value.code == null }

        // A guest of the evening is one until the evening ends.
        val passer = Phone(3, danny, "דני", "מיה")
        val e = pair(passer, tv, KeyLink.AS_EVENING)
        e.stop()
        waitFor("gone") { tv.server.ui.value.phones.isEmpty() }
        evening = "2026-11-28"
        val next = passer.client(KeyClient.Target(setOf(tv.id), KeyLink.AS_EVENING, tv.address), tv)
        next.start()
        waitFor("the next evening they are asked for the code again") { next.state.value is KeyClient.State.NeedCode }
        assertFalse("and the home's own phone is still the home's", tv.store.roleOf(token) != KeyLink.AS_HOME)
    }

    @Test
    fun aNoteWaitsForTheTvOfTheCouchItWasWrittenFor() {
        val home = Tv("OurTV", ours, "סטיב", "רוני").start()
        val friend = Tv("DannyTV", danny).start()
        val phone = Phone(1, ours)
        phone.store.me = "a"
        phone.store.keepNote(Note("n-home", "a", null, "לילה טוב", emptyList(), 1_000, to = "b"), ours.id)

        // At the friend's: the note for home stays on the phone.
        val c = pair(phone, friend, KeyLink.AS_EVENING)
        val me = arrive(phone, friend, c, "סטיב")
        settle()
        assertTrue("nothing of the home's reached the friend's TV", Notes.all(friend.repo.snapshot()).isEmpty())
        assertEquals(1, phone.store.pendingNotes().size)
        // A note written there, as the player the holder is there, goes there.
        c.sendNote(Note("n-danny", me.who, null, "תודה על הערב", emptyList(), 2_000, to = "a"), couch = danny.id)
        waitFor("the friend's TV keeps the note written for it") { Notes.all(friend.repo.snapshot()).any { it.id == "n-danny" } }
        assertEquals(me.who, Notes.all(friend.repo.snapshot()).first { it.id == "n-danny" }.from)
        waitFor("only the home's note still waits") { phone.store.pendingNotes().map { it.id } == listOf("n-home") }

        // Back at home: the home's note goes to the home's TV.
        c.retarget(KeyClient.Target(setOf(home.id), KeyLink.AS_HOME, home.address))
        waitFor("the home's TV asks for a code") { home.server.ui.value.code != null && c.state.value is KeyClient.State.NeedCode }
        c.sendCode(home.server.ui.value.code!!)
        waitFor("the home's TV keeps its note") { Notes.all(home.repo.snapshot()).any { it.id == "n-home" } }
        assertFalse(Notes.all(home.repo.snapshot()).any { it.id == "n-danny" })
        assertNotEquals(home.id, friend.id)
    }
}
