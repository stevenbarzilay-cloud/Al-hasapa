package app.couch.alhasapa.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.data.CouchRef
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.data.Person
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.data.StateStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Couches on a device (2.0): a device from before 2.0 is given its couch and keeps everything it had; a visit begins, ends, and
 * never begins again by itself; the home changes only when asked, and what it was is kept.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class CouchSetupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Memory(@Volatile var text: String? = null) : StateStore {
        override fun read() = text
        override fun write(json: String) { text = json }
    }

    /** A state as 1.12 left it: the two of us, a month of evenings, a friend who comes often; no couch. */
    private fun from112(): String = JSONObject().put("v", 1)
        .put("names", JSONObject().put("a", "סטיב").put("b", "רוני"))
        .put("pieces", JSONObject().put("a", JSONObject().put("shape", "star").put("color", "blue")))
        .put("prefs", JSONObject().put("framed", "yes"))
        .put("people", JSONObject().put("u1a2b3c4", JSONObject().put("name", "דני").put("shape", "circle").put("color", "green").put("at", 5)))
        .put("log", JSONObject().put("2026-11-20", JSONObject().put("wordle", JSONObject().put("w", "a").put("r", JSONObject().put("v", 3)).put("t", 100L))))
        .put("solo", JSONObject().put("2026-11-20", JSONObject().put("bandle", JSONObject().put("a", JSONObject().put("v", 2).put("t", 200L)))))
        .toString()

    private fun without(state: JSONObject, vararg keys: String): String = JSONObject(state.toString()).also { s -> keys.forEach { s.remove(it) } }.toString()

    @Test
    fun aTvAndItsPhoneFromBefore20AreOfOneCouchAndKeepEverything() {
        val relay = RelayConfig.create()
        RelayStore(context).config = relay
        val before = from112()
        // The TV names its couch (from the topic of the relay it made).
        val tv = StateRepository(Memory(before))
        CouchSetup.migrate(context, tv, tv = true)
        val id = Couches.idFromTopic(relay.topic)
        assertEquals(CouchRef(id, "הספה של סטיב ורוני"), Couches.of(tv.snapshot()))
        // Its phone, paired with it before 2.0: of that TV's couch. Nothing is made up meanwhile, and nothing of its data is touched.
        RemoteStore(context).saveToken("tv0001", "t".repeat(64))
        val phone = StateRepository(Memory(before))
        CouchStore(context).migrated = false
        CouchSetup.migrate(context, phone, tv = false)
        assertEquals(JSONObject(before).toString(), phone.snapshot().toString())
        assertEquals("the TV it was paired with is the home's", setOf("tv0001"), CouchStore(context).homeTvs)
        assertEquals(KeyClient.Target(setOf("tv0001"), KeyLink.AS_HOME), Links.targetFor(context))
        // They meet (at home, or through the relay): the phone learns its couch from what the TV says.
        for (share in app.couch.alhasapa.data.Shared.chunks(tv.snapshot(), listOf("2026-11-20"))) phone.mergeShared(share)
        assertEquals("the same couch on both", Couches.of(tv.snapshot()), Couches.of(phone.snapshot()))
        // Names, pieces, points, history, the games played alone, the friend who comes often: all as they were, on both.
        for (r in listOf(tv, phone)) {
            assertEquals(JSONObject(before).toString(), JSONObject(without(r.snapshot(), Couches.KEY, "stamps", "gone")).toString())
            assertEquals("סטיב" to "רוני", CouchState.names(r.snapshot()))
            assertEquals(listOf("סטיב", "רוני", "דני"), Couple.all(r.snapshot()).map { it.name })
        }
        assertEquals("the pairing is kept", "t".repeat(64), RemoteStore(context).tokenFor("tv0001"))
        assertEquals("and the relay", relay, RelayStore(context).config)
        // Done once: nothing changes the second time.
        val again = phone.json()
        CouchSetup.migrate(context, phone, tv = false)
        assertEquals(again, phone.json())

        // A phone that knew several TVs and holds a relay is of the couch of the TV that gave it: the same id, from the same topic.
        val couches = CouchStore(context)
        couches.migrated = false; couches.homeTvs = emptySet()
        RemoteStore(context).saveToken("tv0002", "u".repeat(64))
        val several = StateRepository(Memory(before))
        CouchSetup.migrate(context, several, tv = false)
        assertEquals(id, Couches.of(several.snapshot())!!.id)
        assertEquals("each is taken for the home's until it says it is of another couch", setOf("tv0001", "tv0002"), couches.homeTvs)
        CouchSetup.otherCouch(context, KeyClient.Tv("tv0002", "DannyTV", CouchRef("c1111111111111aa", "הספה של דני")))
        assertEquals(setOf("tv0001"), couches.homeTvs)
        assertEquals("and that one is kept among the friends' couches", listOf("הספה של דני"), couches.saved().map { it.name })
        RelayStore(context).config = null
    }

    @Test
    fun aPhoneWithoutTheRelayLearnsItsCouchFromItsOneTvAndOneWithSeveralAsksOnce() {
        RelayStore(context).config = null
        // One TV: it is the home's, and the couch's id is learnt from it when they next meet. The data is not touched meanwhile.
        RemoteStore(context).saveToken("tv0001", "t".repeat(64))
        val one = StateRepository(Memory(from112()))
        val before = one.json()
        CouchSetup.migrate(context, one, tv = false)
        assertNull("no id is made up", Couches.of(one.snapshot()))
        assertEquals(before, one.json())
        assertEquals(setOf("tv0001"), CouchStore(context).homeTvs)
        assertTrue(CouchStore(context).askHome.isEmpty())
        assertFalse("and it is not taken for a new device", Couches.needsSetup(one.snapshot()))
        assertEquals("it is shown by its players' names meanwhile", "הספה של סטיב ורוני", Couches.name(one.snapshot()))
        assertEquals("the connection goes to that TV, as one of the home's own", KeyClient.Target(setOf("tv0001"), KeyLink.AS_HOME), Links.targetFor(context))

        // Several TVs, and no way to tell which is the home's: asked once, and nothing is sent to any of them meanwhile.
        val couches = CouchStore(context)
        couches.migrated = false; couches.homeTvs = emptySet()
        RemoteStore(context).saveToken("tv0002", "u".repeat(64))
        val two = StateRepository(Memory(from112()))
        CouchSetup.migrate(context, two, tv = false)
        assertEquals(setOf("tv0001", "tv0002"), couches.askHome)
        assertTrue(couches.homeTvs.isEmpty())
        assertEquals(from112().length, without(two.snapshot()).length)
        assertEquals("it only asks each which couch it is of", KeyClient.Target.ASK, Links.targetFor(context)!!.role)
        // "No, I am a guest here": that one is kept among the friends' couches; the other is still to be asked about.
        CouchSetup.answerHome(context, KeyClient.Tv("tv0002", "DannyTV", CouchRef("c1111111111111aa", "הספה של דני")), home = false)
        assertEquals(setOf("tv0001"), couches.askHome)
        assertEquals(listOf("הספה של דני"), couches.saved().map { it.name })
        // "Yes, this is my home's".
        CouchSetup.answerHome(context, KeyClient.Tv("tv0001", "OurTV", CouchRef("c2222222222222bb", "הספה של סטיב ורוני")), home = true)
        assertTrue(couches.askHome.isEmpty())
        assertEquals(setOf("tv0001"), couches.homeTvs)
        assertEquals(KeyClient.Target(setOf("tv0001"), KeyLink.AS_HOME), Links.targetFor(context))
    }

    @Test
    fun aPhoneThatNeverMetATvHasACouchOfItsOwnAndANewDeviceWaitsForItsFirstChoice() {
        RelayStore(context).config = null
        val alone = StateRepository(Memory(from112()))
        CouchSetup.migrate(context, alone, tv = false)
        assertNotNull(Couches.of(alone.snapshot()))
        assertNull("no TV: nothing to connect to", Links.targetFor(context))
        // A new device: nothing is decided for it.
        CouchStore(context).migrated = false
        val fresh = StateRepository(Memory())
        CouchSetup.migrate(context, fresh, tv = false)
        assertNull(Couches.of(fresh.snapshot()))
        assertTrue(Couches.needsSetup(fresh.snapshot()))
        // On a TV, one press: a couch with nothing in it, its names not stamped (the first phone's names win over them).
        CouchStore(context).migrated = false
        val tvState = StateRepository(Memory())
        CouchSetup.create(context, tvState, "", "", "", now = 1_000)
        assertTrue(Couches.bare(tvState.snapshot()))
        assertEquals(0L, Couple.stamp(tvState.snapshot(), "a"))
        assertTrue(Couple.offer(tvState.snapshot(), Person("a", "נועה", "", "", 500)))
        // Its first choice: a couch of its own, with the names it was given and nothing played.
        val c = CouchSetup.create(context, fresh, "", "נועה", "יואב", now = 1_000)
        assertEquals("הספה של נועה ויואב", c.name)
        assertEquals("נועה" to "יואב", CouchState.names(fresh.snapshot()))
        assertEquals(0, fresh.snapshot().getJSONObject("log").length())
        assertFalse(fresh.json().contains("סטיב") || fresh.json().contains("רוני"))
        assertFalse(Couches.needsSetup(fresh.snapshot()))
    }

    @Test
    fun aVisitEndsWhenAskedOrWithItsEveningAndNeverBeginsAgainByItself() {
        val couches = CouchStore(context)
        val remote = RemoteStore(context)
        couches.homeTvs = setOf("tv0001")
        val danny = CouchRef("c1111111111111aa", "הספה של דני")
        // A guest of the evening.
        val tonight = Couple.evening()
        val tomorrow = CouchState.dayKey(java.time.LocalDate.parse(tonight).plusDays(1))
        remote.saveToken("tv0009", "g".repeat(64)); remote.setAddress("tv0009", "192.168.1.9:47123")
        val me = Person("u7654321", "סטיב", "", "", 10, guest = true, day = tonight)
        CouchSetup.startVisit(context, danny, "tv0009", me, kept = false, evening = tonight, added = true)
        assertEquals("the home's TVs are the home's still", setOf("tv0001"), couches.homeTvs)
        assertEquals("the connection goes to the friend's TV, as a guest of the evening",
            KeyClient.Target(setOf("tv0009"), KeyLink.AS_EVENING, "192.168.1.9:47123"), Links.targetFor(context))
        assertEquals("until five in the morning it is the same evening", "tv0009", couches.visitNow(tonight)!!.tv)
        // The evening ended: at home again, and a couch that was for the evening only is forgotten, its pairing too.
        assertNull(couches.visitNow(tomorrow))
        assertNull(couches.visit)
        assertNull(couches.saved(danny.id))
        assertEquals("", remote.tokenFor("tv0009"))
        assertEquals(KeyClient.Target(setOf("tv0001"), KeyLink.AS_HOME), Links.targetFor(context))

        // A couch kept for the next visits.
        remote.saveToken("tv0009", "h".repeat(64))
        val often = Person("u1234567", "סטיב", "", "", 20)
        CouchSetup.startVisit(context, danny, "tv0009", often, kept = true, evening = tonight, added = true)
        assertEquals(KeyLink.AS_GUEST, Links.targetFor(context)!!.role)
        CouchSetup.endVisit(context)
        assertNull(couches.visit)
        val saved = couches.saved(danny.id)!!
        assertEquals("who the holder is there is remembered, for the next visit", "u1234567" to "סטיב", saved.me to saved.meName)
        assertEquals("h".repeat(64), remote.tokenFor("tv0009"))
        assertEquals("the next day the phone is at home: a visit begins only when the person begins it",
            KeyClient.Target(setOf("tv0001"), KeyLink.AS_HOME), Links.targetFor(context))
        // Taken off the list: gone from this phone only.
        CouchSetup.remove(context, danny.id)
        assertTrue(couches.saved().isEmpty())
        assertEquals("", remote.tokenFor("tv0009"))
        assertEquals(setOf("tv0001"), couches.homeTvs)
    }

    @Test
    fun theHomeChangesOnlyWhenAskedAndWhatItWasIsKeptWhole() {
        val couches = CouchStore(context)
        val remote = RemoteStore(context)
        val store = Memory(from112())
        val repo = StateRepository(store)
        val ours = Couches.newId()
        repo.update { Couches.set(it, ours, "", 0L) }
        couches.homeTvs = setOf("tv0001")
        remote.me = "a"
        RelayStore(context).config = RelayConfig.create()
        val oldState = repo.json()

        val danny = CouchRef("c1111111111111aa", "הספה של דני")
        CouchSetup.setHome(context, repo, danny, listOf("tv0009"), now = 5_000)
        // The new home: its id, and nothing of the old one in it.
        assertEquals(danny.id, Couches.of(repo.snapshot())!!.id)
        assertEquals("nothing played until now moved", 0, repo.snapshot().getJSONObject("log").length())
        assertNull(repo.snapshot().optJSONObject("solo"))
        assertEquals(setOf("tv0009"), couches.homeTvs)
        assertNull("who holds the phone is asked again at the new home", remote.me)
        assertNull("the old home's channel is not this home's", RelayStore(context).config)
        // The home that was: among the saved couches, whole.
        val was = couches.saved(ours)!!
        assertTrue(was.archived && was.kept)
        assertEquals(listOf("tv0001"), was.tvs)
        assertEquals("a" to "סטיב", was.me to was.meName)

        // Back: what it was comes back as it was.
        CouchSetup.setHome(context, repo, CouchRef(ours, "הספה של סטיב ורוני"), listOf("tv0001"), now = 6_000)
        assertEquals(JSONObject(oldState).toString(), repo.snapshot().toString())
        assertEquals("a", remote.me)
        assertEquals(setOf("tv0001"), couches.homeTvs)
        assertNull("the couch that is the home is not also a saved one", couches.saved(ours))
        assertNotNull("Danny's, which was the home for a while, is kept", couches.saved(danny.id))
    }

    @Test
    fun aStateFromBefore20ThatHasNotLearntItsCouchYetIsKeptWhenTheHomeChanges() {
        val couches = CouchStore(context)
        // A phone from 1.12, paired with one TV it has not met since the update: its couch has no id yet.
        RemoteStore(context).saveToken("tv0001", "t".repeat(64))
        val repo = StateRepository(Memory(from112()))
        CouchSetup.migrate(context, repo, tv = false)
        assertNull(Couches.of(repo.snapshot()))
        val danny = CouchRef("c1111111111111aa", "הספה של דני")
        CouchSetup.setHome(context, repo, danny, listOf("tv0009"), now = 5_000)
        assertEquals(danny.id, Couches.of(repo.snapshot())!!.id)
        // What it had is not lost: it is a saved couch now, whole, and can be the home again.
        val was = couches.saved().single { it.archived }
        assertEquals("הספה של סטיב ורוני", was.name)
        CouchSetup.setHome(context, repo, CouchRef(was.id, was.name), was.tvs, now = 6_000)
        assertNotNull(CouchState.entry(repo.snapshot(), "2026-11-20", "wordle"))
        assertEquals("סטיב" to "רוני", CouchState.names(repo.snapshot()))
        assertEquals(listOf("סטיב", "רוני", "דני"), Couple.all(repo.snapshot()).map { it.name })
        // And a game said to be the home's before the home had an id is the home's still.
        val before = app.couch.alhasapa.remote.SoloTarget("", "הספה של סטיב ורוני", "a", "סטיב", home = true)
        assertTrue(app.couch.alhasapa.results.SoloTargets.isHome(before, repo))
        assertFalse(app.couch.alhasapa.results.SoloTargets.isHome(before.copy(couch = danny.id), repo))
    }
}

/** Finding a TV on the network (2.0): which of those found may be one of the TVs that are meant. */
class FoundTvTest {
    @Test
    fun aTvIsMeantByItsIdOrByTheBeginningOfItInItsNameWhenTheNetworkDoesNotSayIt() {
        val meant = setOf("ab12cd34ef56")
        assertTrue(FoundTv("192.168.1.5:47123", "ab12cd34ef56").mayBe(meant))
        assertFalse("another TV, by its id", FoundTv("192.168.1.6:47123", "ffff00001111", "ab12").mayBe(meant))
        assertTrue("its id was not said, its name begins like it: tried, and what it says of itself decides", FoundTv("192.168.1.7:47123", "", "ab12").mayBe(meant))
        assertFalse(FoundTv("192.168.1.8:47123", "", "9999").mayBe(meant))
        assertFalse(FoundTv("192.168.1.9:47123", "", "").mayBe(meant))
    }
}
