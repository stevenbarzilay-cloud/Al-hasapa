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
    private val server = KeyServer(
        context, onInput = { typed += it }, preferredPort = 0, advertise = false, tvName = "MiBOX4",
        onLogins = { payload -> received += payload; "NYT Games" to 3 },
    )
    private val clients = ArrayList<KeyClient>()

    @After fun close() { clients.forEach { it.stop() }; server.stop() }

    private fun client(): KeyClient = KeyClient(context, phoneName = "Pixel 8", find = { "127.0.0.1:${server.port}" }).also { clients += it }

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
