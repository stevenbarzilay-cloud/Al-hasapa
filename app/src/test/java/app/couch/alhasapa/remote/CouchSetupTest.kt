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
    fun theTvAndThePhonesItGaveItsRelayToAgreeOnTheirCouchAndKeepEverything() {
        val relay = RelayConfig.create()
        RelayStore(context).config = relay
        val before = from112()
        // The TV.
        val tv = StateRepository(Memory(before))
        CouchSetup.migrate(context, tv, tv = true)
        // A phone that holds the same relay, paired with that TV.
        RemoteStore(context).saveToken("tv0001", "t".repeat(64))
        val phone = StateRepository(Memory(before))
        CouchStore(context).migrated = false
        CouchSetup.migrate(context, phone, tv = false)

        val id = Couches.idFromTopic(relay.topic)
        assertEquals("the same couch on both, without their talking", CouchRef(id, "הספה של סטיב ורוני"), Couches.of(tv.snapshot()))
        assertEquals(Couches.of(tv.snapshot()), Couches.of(phone.snapshot()))
        assertEquals("the TV it was paired with is taken for the home's", setOf("tv0001"), CouchStore(context).homeTvs)
        // Names, pieces, points, history, the games played alone, the friend who comes often: all as they were.
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
}
