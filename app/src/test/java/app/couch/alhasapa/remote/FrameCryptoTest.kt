package app.couch.alhasapa.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The pictures of touch mode: sealed for the paired phone of this connection only, and refused when anything about them is wrong. */
class FrameCryptoTest {
    private val token = "ab".repeat(32)
    private val picture = ByteArray(5_000) { (it * 31).toByte() }

    @Test
    fun bothSidesMakeTheSameKeyFromTheTokenAndTheConnectionsNonce() {
        val tv = FrameCrypto.key(token, "0f".repeat(16))
        val phone = FrameCrypto.key(token, "0f".repeat(16))
        assertArrayEquals(tv, phone)
        assertEquals(32, tv.size)
        assertFalse("a new connection, a new key", tv.contentEquals(FrameCrypto.key(token, "1e".repeat(16))))
        assertFalse("another phone's token, another key", tv.contentEquals(FrameCrypto.key("cd".repeat(32), "0f".repeat(16))))
    }

    @Test
    fun aPictureOpensWholeWithTheRightKeyAndNumberOnly() {
        val key = FrameCrypto.key(token, "0f".repeat(16))
        val (iv, data) = FrameCrypto.seal(key, 7, picture)
        assertArrayEquals(picture, FrameCrypto.open(key, 7, iv, data))
        assertNull("passed off as another picture", FrameCrypto.open(key, 8, iv, data))
        assertNull("another connection's key", FrameCrypto.open(FrameCrypto.key(token, "1e".repeat(16)), 7, iv, data))
        val changed = data.toCharArray().also { it[40] = if (it[40] == 'A') 'B' else 'A' }.concatToString()
        assertNull("a changed byte", FrameCrypto.open(key, 7, iv, changed))
        assertNull("not Base64", FrameCrypto.open(key, 7, "!!", data))
        // Sealed twice, the same picture never looks the same on the network.
        assertFalse(FrameCrypto.seal(key, 7, picture).second == data)
    }
}
