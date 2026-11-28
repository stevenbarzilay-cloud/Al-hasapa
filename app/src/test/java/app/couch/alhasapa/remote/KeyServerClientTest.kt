package app.couch.alhasapa.remote

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.remote.KeyLink.Msg
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The TV's server and the phone's client, talking over a real socket on this machine: pairing, typing, coming back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class KeyServerClientTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val typed = CopyOnWriteArrayList<Msg>()
    private val received = CopyOnWriteArrayList<String>()
    private val linked = CopyOnWriteArrayList<Boolean>()
    /** Touch mode: what the TV pictures (null: no game is open), and whether a phone shows it, as the TV was told. */
    @Volatile private var source: FrameSource? = null
    private val mirroring = CopyOnWriteArrayList<Boolean>()
    /** Each of us as each side knows us, and the notes the TV kept (1.9.9). */
    private class Book(vararg people: app.couch.alhasapa.data.Person) : CoupleBook {
        val state: org.json.JSONObject = app.couch.alhasapa.data.CouchState.empty().also { s -> people.forEach { app.couch.alhasapa.data.Couple.put(s, it) } }
        val notes = CopyOnWriteArrayList<app.couch.alhasapa.data.Note>()
        @Synchronized override fun couple() = app.couch.alhasapa.data.Couple.all(state)
        @Synchronized override fun offer(people: List<app.couch.alhasapa.data.Person>) = people.map { app.couch.alhasapa.data.Couple.offer(state, it) }.any { it }
        override fun note(n: app.couch.alhasapa.data.Note): Boolean = if (notes.any { it.id == n.id }) false else { notes += n; true }
    }
    private val tvBook = Book(app.couch.alhasapa.data.Person("a", "סטיב", "circle", "red", 100), app.couch.alhasapa.data.Person("b", "רוני", "triangle", "pink", 100))
    /** The TV's solo relay (1.11), kept apart from the phone's own store (both live in this one app here). */
    private val relayCfg = RelayConfig.create()
    private val server = KeyServer(
        context, onInput = { typed += it }, preferredPort = 0, advertise = false, tvName = "MiBOX4",
        onLogins = { payload -> received += payload; "NYT Games" to 3 },
        appCode = 28, appVer = "1.9.4", onLinked = { on -> if (linked.lastOrNull() != on) linked += on },
        frameSource = { source }, onMirroring = { mirroring += it }, book = tvBook, relay = { relayCfg },
    )
    private val clients = ArrayList<KeyClient>()

    @After fun close() { clients.forEach { it.stop() }; server.stop() }

    private fun client(appCode: Int = 28, appVer: String = "1.9.4"): KeyClient =
        KeyClient(context, phoneName = "Pixel 8", find = { "127.0.0.1:${server.port}" }, appCode = appCode, appVer = appVer).also { clients += it }

    /** Waits (letting the main thread run, where the server hands over what was typed) until [ok], or fails with [what]. */
    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ok()) return
            Thread.sleep(20)
        }
        fail("timed out waiting for: $what")
    }

    /** As [waitFor], with the clock of the main thread moving on too (the pictures are paced by it). */
    private fun waitTicking(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25))
            if (ok()) return
            Thread.sleep(10)
        }
        fail("timed out waiting for: $what")
    }

    private fun paired(book: CoupleBook? = null): KeyClient {
        RemoteStore(context).forgetPhones()
        server.start()
        val phone = if (book == null) client() else
            KeyClient(context, phoneName = "Pixel 8", find = { "127.0.0.1:${server.port}" }, appCode = 28, appVer = "1.9.4", book = book).also { clients += it }
        phone.start()
        waitFor("the TV asks for a code") { phone.state.value is KeyClient.State.NeedCode }
        phone.sendCode(server.ui.value.code!!)
        waitFor("connected") { phone.state.value is KeyClient.State.Connected }
        return phone
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun inTouchModeThePhoneIsShownTheGameOnePictureAtATimeAndItsFingerComesBack() {
        val phone = paired()
        server.touchGame = true
        waitFor("the phone hears that this game is played by touch") { (phone.state.value as? KeyClient.State.Connected)?.touch == true }

        phone.mirror(true, 480)
        waitTicking("no game is open yet, and the phone is told so") { phone.frameNone.value == KeyLink.NONE_HOME }
        assertEquals("the TV knows a phone shows the game", listOf(true), mirroring)

        // A game: pictures of its page, a little different each time (as a page that moves is).
        val asked = CopyOnWriteArrayList<Int>()
        var shade = 0
        val crops = CopyOnWriteArrayList<String>()
        source = FrameSource { w, crop, done ->
            asked += w
            crops += crop?.toString() ?: "all"
            shade = (shade + 50) % 250
            done(android.graphics.Bitmap.createBitmap(64, 36, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(shade, 90, 40)) })
        }
        server.framesChanged()
        waitTicking("a picture arrives") { phone.frame.value != null }
        val first = phone.frame.value!!
        assertEquals(64, first.w); assertEquals(36, first.h)
        assertEquals("asked as wide as the phone draws it", 480, asked.first())
        assertEquals("a JPEG, opened whole on the phone", 0xFF.toByte(), first.jpeg[0]); assertEquals(0xD8.toByte(), first.jpeg[1])
        assertNull(phone.frameNone.value)

        // One at a time: nothing more until the phone says it showed it.
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(10) }
        assertEquals(first.n, phone.frame.value!!.n)
        phone.frameShown(first.n)
        waitTicking("the next picture") { phone.frame.value!!.n > first.n }

        // Zoomed in on the phone: the next picture is of that part of the page, and says so.
        val part = KeyLink.Crop(0.25f, 0.25f, 0.75f, 0.75f)
        phone.mirror(true, 480, part)
        waitTicking("a picture of the part in view") { phone.frame.value?.crop == part }
        assertTrue("the TV was asked for that part", crops.contains(part.toString()))

        // The finger on the picture comes back to the TV as it was.
        phone.send(Msg.Touch(KeyLink.TouchAction.DOWN, 0.5f, 0.25f))
        phone.send(Msg.Touch(KeyLink.TouchAction.UP, 0.5f, 0.25f))
        waitFor("the touch reached the TV") { typed.count { it is Msg.Touch } == 2 }
        assertEquals(Msg.Touch(KeyLink.TouchAction.DOWN, 0.5f, 0.25f), typed.first { it is Msg.Touch })

        // The game closes: the phone hears there is nothing to show; touch mode off: the TV's paw may come back.
        source = null; server.framesChanged()
        waitTicking("told the game is gone") { phone.frameNone.value == KeyLink.NONE_HOME && phone.frame.value == null }
        phone.mirror(false, 0)
        waitTicking("the TV knows no phone shows the game") { mirroring.lastOrNull() == false }
    }

    @Test
    fun eachOfUsChangesTheirOwnLookOnTheirPhoneAndTheTvAndTheOtherPhoneFollow() {
        // Roni's phone: Roni changed her name and colour there (newer than the TV's); it also knows an older Steve.
        RemoteStore(context).me = "b"
        val roni = Book(app.couch.alhasapa.data.Person("a", "סטיבי", "circle", "red", 50), app.couch.alhasapa.data.Person("b", "רונית", "heart", "teal", 500))
        val phone = paired(roni)
        waitFor("the TV took Roni's newer look") { tvBook.couple()[1].name == "רונית" }
        assertEquals("heart", tvBook.couple()[1].shape)
        assertEquals("the TV's Steve is newer: it stays", "סטיב", tvBook.couple()[0].name)
        waitFor("and the phone took the TV's newer Steve") { roni.couple()[0].name == "סטיב" }
        waitFor("the TV shows the phone by who holds it") { server.ui.value.phones == listOf("רונית") }

        // A change on the phone later goes at once.
        roni.offer(listOf(app.couch.alhasapa.data.Person("b", "רוני", "star", "purple", 900)))
        phone.sendCouple()
        waitFor("the TV follows") { tvBook.couple()[1].shape == "star" }
        RemoteStore(context).me = null
    }

    @Test
    fun aNoteWrittenAwayFromTheTvWaitsOnThePhoneAndArrivesOnce() {
        RemoteStore(context).me = "a"
        val store = RemoteStore(context)
        store.pendingNotes().forEach { store.noteKept(it.id) }
        val note = app.couch.alhasapa.data.Note("n7", "b", "krillion", "בהצלחה הערב", listOf(intArrayOf(0, 0, 900, 900)), 1_000)
        // Written while not connected: kept on the phone.
        val offline = KeyClient(context, phoneName = "Pixel 8", find = { null }, appCode = 28, appVer = "1.9.4", book = Book()).also { clients += it }
        offline.sendNote(note)
        assertEquals(1, offline.notesWaiting.value)
        // Connected: it goes, the TV keeps it once (from whoever holds the phone), and the phone stops keeping it.
        val phone = paired(Book())
        waitFor("the TV kept the note") { tvBook.notes.size == 1 }
        assertEquals("a", tvBook.notes[0].from)
        assertEquals("krillion", tvBook.notes[0].game)
        waitFor("nothing waits on the phone") { RemoteStore(context).pendingNotes().isEmpty() }
        phone.sendNote(note)
        Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("sent again, kept once", 1, tvBook.notes.size)
        RemoteStore(context).me = null
    }

    @Test
    fun aNoteWrittenOnThePhonesHomeReachesTheTvWhenTheAppNextOpensWithoutKeyboardMode() {
        // A phone that has met the TV before (it knows where it is and is paired), now away from keyboard mode. (Android 17 asks the
        // person once to let the app reach devices at home; here it was allowed.)
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(PhoneSync.LOCAL_NETWORK)
        val first = paired(Book())
        first.stop()
        waitFor("the phone left") { server.ui.value.phones.isEmpty() }
        val store = RemoteStore(context)
        store.me = "u1a2b3c4"
        store.pendingNotes().forEach { store.noteKept(it.id) }
        store.keepNote(app.couch.alhasapa.data.Note("h1", "u1a2b3c4", null, "ערב טוב לכולם", emptyList(), 1_000, to = "a"))
        val guest = app.couch.alhasapa.data.Person("u1a2b3c4", "דני", "star", "green", 500)
        val phoneBook = Book(guest)
        var done = 0
        val sync = PhoneSync(context, newClient = {
            KeyClient(context, phoneName = "Pixel 8", find = { "127.0.0.1:${server.port}" }, appCode = 28, appVer = "1.9.4", book = phoneBook).also { clients += it }
        }, newest = { 500 }, onDone = { done++ })
        assertTrue("something waits", sync.waiting())
        // (2.0: the phone goes to its home's own TV only; this one is it.)
        CouchStore(context).homeTvs = setOf(RemoteStore(context).tvId)
        sync.start()
        waitFor("the note reached the TV") { tvBook.notes.any { it.id == "h1" } }
        assertEquals("from the one who holds the phone", "u1a2b3c4", tvBook.notes.first { it.id == "h1" }.from)
        assertEquals("a", tvBook.notes.first { it.id == "h1" }.to)
        waitFor("and who they are, too") { tvBook.couple().any { it.who == "u1a2b3c4" && it.name == "דני" } }
        waitTicking("the phone lets go once it is handed over") { !sync.waiting() && server.ui.value.phones.isEmpty() }
        assertEquals("and the home is told, once (its ✉️ count goes)", 1, done)
        store.me = null
    }

    private class MemoryState : app.couch.alhasapa.data.StateStore {
        var text: String? = null
        override fun read() = text
        override fun write(json: String) { text = json }
    }

    /**
     * 1.12: the TV and a phone keep the same evenings, library and looks. When they meet, each tells the other what it keeps (the first
     * time every evening, however old); after that every change made on one reaches the other at once, while they are connected.
     */
    @Test
    fun theTvAndThePhoneKeepTheSameEveningsAndEveryChangeReachesTheOther() {
        context.getSharedPreferences("remote", Context.MODE_PRIVATE).edit().clear().commit()
        val today = java.time.LocalDate.now()
        fun key(daysAgo: Long) = app.couch.alhasapa.data.CouchState.dayKey(today.minusDays(daysAgo))
        fun entry(v: Int, t: Long) = org.json.JSONObject().put("w", "").put("t", t)
            .put("r", org.json.JSONObject().put("v", v).put("max", 6).put("fail", false).put("kind", "guesses"))
        fun pts(r: app.couch.alhasapa.data.StateRepository, day: String) =
            app.couch.alhasapa.scoring.Scoring.dayPts(r.snapshot().optJSONObject("log") ?: org.json.JSONObject(), day)
        val now = System.currentTimeMillis()
        val tvRepo = app.couch.alhasapa.data.StateRepository(MemoryState()).also { it.onLocalChange = { ShareBus.changed() } }
        val phoneRepo = app.couch.alhasapa.data.StateRepository(MemoryState()).also { it.onLocalChange = { ShareBus.changed() } }
        tvRepo.update { s ->
            app.couch.alhasapa.data.CouchState.setEntry(s, key(1), "wordle", entry(3, now - 86_400_000))
            app.couch.alhasapa.data.CouchState.setEntry(s, key(40), "connections", entry(2, now - 40 * 86_400_000L))
            s.put("prefs", org.json.JSONObject().put("pips", "no"))
        }
        phoneRepo.update { s ->
            app.couch.alhasapa.data.CouchState.setEntry(s, key(0), "bandle", entry(2, now - 60_000))
            app.couch.alhasapa.data.CouchState.setEntry(s, key(50), "strands", entry(1, now - 50 * 86_400_000L))
            app.couch.alhasapa.data.Couple.put(s, app.couch.alhasapa.data.Person("b", "רוני", "star", "blue", now - 30_000))
        }
        val tv = KeyServer(
            context, onInput = {}, preferredPort = 0, advertise = false, tvName = "MiBOX4", onLogins = { null },
            onLinked = {}, frameSource = { null }, onMirroring = {}, book = RepoCoupleBook { tvRepo }, relay = { null },
        )
        tv.start()
        try {
            val phone = KeyClient(context, phoneName = "Pixel 8", find = { "127.0.0.1:${tv.port}" }, book = RepoCoupleBook { phoneRepo }).also { clients += it }
            phone.start()
            waitFor("the TV asks for a code") { phone.state.value is KeyClient.State.NeedCode }
            phone.sendCode(tv.ui.value.code!!)
            waitFor("connected") { phone.state.value is KeyClient.State.Connected }
            // They meet for the first time: every evening kept, however old, both ways; the library and the looks too.
            waitFor("the phone has the TV's evenings, the old one too") {
                val s = phoneRepo.snapshot()
                app.couch.alhasapa.data.CouchState.entry(s, key(1), "wordle") != null && app.couch.alhasapa.data.CouchState.entry(s, key(40), "connections") != null
            }
            waitFor("the TV has the phone's") {
                val s = tvRepo.snapshot()
                app.couch.alhasapa.data.CouchState.entry(s, key(0), "bandle") != null && app.couch.alhasapa.data.CouchState.entry(s, key(50), "strands") != null
            }
            waitFor("said once: all of it is alike now") { !phone.awaitingAll && RemoteStore(context).sharedAll(RemoteStore(context).tvId) }
            assertEquals("no", phoneRepo.snapshot().getJSONObject("prefs").getString("pips"))
            assertEquals("blue", app.couch.alhasapa.data.Couple.person(tvRepo.snapshot(), "b").color)
            assertEquals(pts(tvRepo, key(0)), pts(phoneRepo, key(0)))
            assertEquals(pts(tvRepo, key(1)), pts(phoneRepo, key(1)))
            // A result saved on the TV: on the phone at once, the same points.
            tvRepo.update { s -> app.couch.alhasapa.data.CouchState.setEntry(s, key(0), "wordle", entry(4, System.currentTimeMillis())) }
            waitFor("the TV's new result is on the phone") { app.couch.alhasapa.data.CouchState.entry(phoneRepo.snapshot(), key(0), "wordle") != null }
            assertEquals(pts(tvRepo, key(0)), pts(phoneRepo, key(0)))
            assertTrue(pts(phoneRepo, key(0)) > 0)
            // A tick taken back on the phone: gone on the TV; a game put on the shelf there: on the TV's shelf.
            phoneRepo.update { s ->
                app.couch.alhasapa.data.CouchState.setEntry(s, key(0), "bandle", null)
                s.getJSONObject("prefs").put("tiles", "yes")
            }
            waitFor("taken back on the TV too") { app.couch.alhasapa.data.CouchState.entry(tvRepo.snapshot(), key(0), "bandle") == null }
            waitFor("and the shelf") { tvRepo.snapshot().optJSONObject("prefs")?.optString("tiles") == "yes" }
            assertEquals(pts(tvRepo, key(0)), pts(phoneRepo, key(0)))
        } finally { tv.stop() }
    }

    /** 1.11: the TV hands each paired phone the solo relay, sealed under that connection's key; the phone keeps it and looks by itself. */
    @Test
    fun theTvHandsAPairedPhoneTheSoloRelay() {
        RelayStore(context).config = null
        val phone = paired()
        waitFor("the phone keeps the TV's relay") { RelayStore(context).config == relayCfg }
        assertTrue(phone.state.value is KeyClient.State.Connected)
        val jobs = context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs
        assertEquals("and looks at it by itself (a phone)", !app.couch.alhasapa.BuildConfig.TV, jobs.any { it.id == SoloJob.JOB_ID })
        RelayStore(context).config = null
    }

    @Test
    fun aNewPhonePairsWithTheCodeShownOnTheTvThenTypesAndComesBackWithoutIt() {
        RemoteStore(context).forgetPhones()
        server.start()
        assertTrue(server.port > 0)
        val phone = client()
        phone.start()
        waitFor("the TV asks for a code") { phone.state.value is KeyClient.State.NeedCode }
        val shown = server.ui.value.code
        assertNotNull("the TV shows the code", shown); assertEquals("Pixel 8", server.ui.value.codeFor)

        phone.sendCode(if (shown == "0000") "1111" else "0000")
        waitFor("a wrong code is said to be wrong") { (phone.state.value as? KeyClient.State.NeedCode)?.left == 2 }
        phone.sendCode(shown!!)
        waitFor("connected") { phone.state.value is KeyClient.State.Connected }
        assertNull("the code is gone from the TV", server.ui.value.code)
        assertEquals(listOf("Pixel 8"), server.ui.value.phones)

        server.screen = "Krillion"
        waitFor("the phone knows what the TV shows") { (phone.state.value as? KeyClient.State.Connected)?.screen == "Krillion" }

        phone.send(Msg.Text("octopus"))
        phone.send(Msg.Press(KeyLink.Key.ENTER, KeyLink.Action.TAP))
        waitFor("what was typed reached the TV, in order") { typed.size == 2 }
        assertEquals(listOf(Msg.Text("octopus"), Msg.Press(KeyLink.Key.ENTER, KeyLink.Action.TAP)), typed.toList())
        phone.send(Msg.Pointer(true)); phone.send(Msg.Move(5f, 6f)); phone.send(Msg.Click); phone.send(Msg.Scroll(-12f))
        waitFor("the touchpad's moves reached the TV") { typed.size == 6 }
        assertEquals(listOf(Msg.Pointer(true), Msg.Move(5f, 6f), Msg.Click, Msg.Scroll(-12f)), typed.drop(2))
        typed.clear()

        // The next time: no code, straight in.
        phone.stop()
        waitFor("the TV saw it leave") { server.ui.value.phones.isEmpty() }
        waitFor("and put its paw away") { typed.contains(Msg.Pointer(false)) }
        val again = client()
        again.start()
        waitFor("connected again without a code") { again.state.value is KeyClient.State.Connected }
        assertNull(server.ui.value.code)
    }

    @Test
    fun aPhoneThatIsNotPairedCannotType() {
        RemoteStore(context).forgetPhones()
        server.start()
        val s = Socket("127.0.0.1", server.port)
        val out = s.getOutputStream().bufferedWriter()
        val input = s.getInputStream().bufferedReader()
        assertTrue(KeyLink.decode(input.readLine()) is Msg.Hi)
        out.write(KeyLink.encode(Msg.Hello("x", "intruder", "f".repeat(64)))); out.write("\n")
        out.write(KeyLink.encode(Msg.Text("rm -rf"))); out.write("\n"); out.flush()
        assertEquals(Msg.NeedCode, KeyLink.decode(input.readLine()))
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("nothing reached the page", typed.isEmpty())
        // Three wrong codes and the connection is closed.
        repeat(3) { out.write(KeyLink.encode(Msg.Pair("9999".takeIf { server.ui.value.code != "9999" } ?: "8888"))); out.write("\n"); out.flush() }
        val replies = generateSequence { runCatching { input.readLine() }.getOrNull() }.mapNotNull { KeyLink.decode(it) }.toList()
        assertEquals(listOf(Msg.Wrong(2), Msg.Wrong(1), Msg.Wrong(0), Msg.Bye("code")), replies)
        s.close()
    }

    @Test
    fun anOverlongLineEndsTheConnection() {
        server.start()
        val s = Socket("127.0.0.1", server.port)
        s.getInputStream().bufferedReader().readLine()
        s.getOutputStream().write(ByteArray(KeyLink.MAX_LINE + 100) { 'x'.code.toByte() })
        s.getOutputStream().flush()
        s.soTimeout = 3_000
        assertEquals("the TV hung up", -1, runCatching { s.getInputStream().read() }.getOrDefault(-1))
        s.close()
    }
    @Test
    fun aReleaseThatChangesOnlyOneOfTheTwoDoesNotSendThePeopleToUpdateTheOther() {
        // What the two tell each other is the version at which they last had to be updated together (raised by 1.9.7, the end of a
        // game, by 1.9.8, touch mode, and by 1.9.9, each of us on our own phone): two apps of releases after it that changed only one
        // of the two have nothing to say to each other about versions.
        assertEquals("raised only by a release that needs both", 41, KeyLink.TOGETHER_SINCE)
        RemoteStore(context).forgetPhones()
        val tv = KeyServer(context, onInput = {}, preferredPort = 0, advertise = false, tvName = "TV", onLogins = { null }, appVer = "1.9.5", onLinked = {})
        tv.start()
        val phone = KeyClient(context, phoneName = "Pixel 8", find = { "127.0.0.1:${tv.port}" }, appVer = "1.9.4").also { clients += it }
        try {
            phone.start()
            waitFor("the TV asks for a code") { phone.state.value is KeyClient.State.NeedCode }
            phone.sendCode(tv.ui.value.code!!)
            waitFor("connected") { phone.state.value is KeyClient.State.Connected }
            assertNull(tv.ui.value.older); assertNull(tv.ui.value.newer); assertNull(phone.versions.value)
        } finally { phone.stop(); tv.stop() }
    }

    @Test
    fun eachSideSaysWhenTheOtherOneWasLeftOnAnOldVersion() {
        // The same version on both: nothing to say. And the TV's Wi-Fi is asked to stay awake exactly while a phone is connected.
        val same = pairedPhone()
        assertNull(server.ui.value.older); assertNull(server.ui.value.newer); assertNull(same.versions.value)
        assertEquals(listOf(false, true), linked.toList().takeLast(2))
        same.stop()
        waitFor("the TV saw it leave") { server.ui.value.phones.isEmpty() }
        assertEquals(false, linked.last())

        // A phone that stayed on 1.9.0 while the TV moved on: the TV names it, and the phone is told there is a newer one.
        val old = client(appCode = 24, appVer = "1.9.0")
        old.start()
        waitFor("the old phone is connected") { old.state.value is KeyClient.State.Connected }
        assertEquals("1.9.0", server.ui.value.older); assertNull(server.ui.value.newer)
        assertEquals(KeyClient.Versions.TvNewer("1.9.4"), old.versions.value)
        old.stop()
        waitFor("it left") { server.ui.value.phones.isEmpty() }
        assertNull("gone with the phone", server.ui.value.older)

        // And the other way round: the phone was updated, the TV was not.
        val new = client(appCode = 29, appVer = "1.9.5")
        new.start()
        waitFor("the newer phone is connected") { new.state.value is KeyClient.State.Connected }
        assertEquals("1.9.5", server.ui.value.newer); assertNull(server.ui.value.older)
        assertEquals(KeyClient.Versions.TvOlder, new.versions.value)
    }

    @Test
    fun theEndOfAGameIsToldToThePhoneAndItsSavePressComesBack() {
        val phone = pairedPhone()
        server.resultReady = true
        waitFor("the phone knows") { (phone.state.value as? KeyClient.State.Connected)?.result == true }
        phone.send(Msg.SaveResult)
        waitFor("the press reached the TV") { typed.contains(Msg.SaveResult) }
        server.resultReady = false
        waitFor("and it went away") { (phone.state.value as? KeyClient.State.Connected)?.result == false }
    }

    @Test
    fun aStrokeOfTheTouchpadIsWrittenDownInNumbersWhenTheFingerRests() {
        val phone = pairedPhone()
        phone.send(Msg.Pointer(true))
        for (i in 0 until 12) { phone.send(Msg.Move(4f, 0f, 1_000L + 16L * i)); Thread.sleep(5) }
        waitFor("the moves reached the TV") { typed.count { it is Msg.Move } == 12 }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(KeyServer.PAD_REST_MS + 100))
        val line = app.couch.alhasapa.diag.Flight.tail(20).lines().lastOrNull { it.contains("touchpad: ") }
        assertNotNull("the stroke is in the report", line)
        assertTrue(line, line!!.contains("touchpad: 12 moves") && line.contains("held up on the way") && line.contains("then waited for the TV"))
    }

    private fun pairedPhone(): KeyClient {
        RemoteStore(context).forgetPhones()
        server.start()
        val phone = client()
        phone.start()
        waitFor("the TV asks for a code") { phone.state.value is KeyClient.State.NeedCode }
        phone.sendCode(server.ui.value.code!!)
        waitFor("connected") { phone.state.value is KeyClient.State.Connected }
        return phone
    }

    @Test
    fun aLoginGoesEncryptedToThePairedTvAndArrivesWhole() {
        val phone = pairedPhone()
        val payload = """{"site":"nyt","cookies":[{"u":"https://nytimes.com/","c":"NYT-S=0^abc; Domain=.nytimes.com; Path=/; Secure"}]}"""
        phone.sendLogins("nyt", payload)
        waitFor("the TV took it") { phone.handoff.value is KeyClient.Handoff.Done }
        assertEquals(listOf(payload), received.toList())
        assertEquals(KeyClient.Handoff.Done("NYT Games", 3), phone.handoff.value)
        // And again on the next connection, which is proved without the token going over the network.
        phone.stop()
        waitFor("the TV saw it leave") { server.ui.value.phones.isEmpty() }
        val again = client()
        again.start()
        waitFor("connected again by proof") { again.state.value is KeyClient.State.Connected }
        again.sendLogins("nyt", payload)
        waitFor("a second login went") { again.handoff.value is KeyClient.Handoff.Done }
        assertEquals(2, received.size)
    }

    @Test
    fun aPhoneThatSendsItsTokenAsIsMayTypeButNotHandOverALogin() {
        pairedPhone().stop()
        waitFor("gone") { server.ui.value.phones.isEmpty() }
        val token = RemoteStore(context).let { store -> store.tokenFor(store.tvId) }
        assertTrue("the phone kept its token", token.length == 64)
        val s = Socket("127.0.0.1", server.port)
        val out = s.getOutputStream().bufferedWriter()
        val input = s.getInputStream().bufferedReader()
        val hi = KeyLink.decode(input.readLine()) as Msg.Hi
        assertTrue("the TV sends a nonce", hi.nonce.length == 32)
        fun say(m: Msg) { out.write(KeyLink.encode(m)); out.write("\n"); out.flush() }
        say(Msg.Hello("old", "old phone", token))                          // as before 1.9
        assertTrue(KeyLink.decode(input.readLine()) is Msg.Ok)
        say(Msg.LoginsKey(LoginCrypto.encode(LoginCrypto.newKeyPair().public)))
        assertEquals(Msg.LoginsNo("update-phone"), KeyLink.decode(input.readLine()))
        say(Msg.Text("still types"))
        waitFor("typing still works") { typed.contains(Msg.Text("still types")) }
        s.close()
    }

    @Test
    fun aWrongProofIsAskedForTheCode() {
        pairedPhone().stop()
        waitFor("gone") { server.ui.value.phones.isEmpty() }
        val s = Socket("127.0.0.1", server.port)
        val out = s.getOutputStream().bufferedWriter()
        val input = s.getInputStream().bufferedReader()
        val hi = KeyLink.decode(input.readLine()) as Msg.Hi
        out.write(KeyLink.encode(Msg.Hello("x", "guess", "", LoginCrypto.proof("d".repeat(64), hi.nonce, "x")))); out.write("\n"); out.flush()
        assertEquals(Msg.NeedCode, KeyLink.decode(input.readLine()))
        s.close()
    }
}
