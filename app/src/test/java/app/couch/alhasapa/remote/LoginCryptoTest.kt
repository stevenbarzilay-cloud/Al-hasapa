package app.couch.alhasapa.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The handoff's crypto: both ends agree on one key, only with the pairing token, and nothing changed on the way is accepted. */
class LoginCryptoTest {
    private val token = "a".repeat(64)

    @Test
    fun hkdfMatchesTheStandardsFirstTestVector() {
        // RFC 5869, A.1
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = LoginCrypto.hkdf(ikm, salt, info, 42).joinToString("") { "%02x".format(it) }
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm)
    }

    @Test
    fun bothEndsAgreeAndTheLoginOpensOnlyThere() {
        val phone = LoginCrypto.newKeyPair(); val tv = LoginCrypto.newKeyPair()
        val pk = LoginCrypto.encode(phone.public); val tk = LoginCrypto.encode(tv.public)
        val onPhone = LoginCrypto.sessionKey(phone.private, LoginCrypto.decode(tk)!!, token, pk, tk)
        val onTv = LoginCrypto.sessionKey(tv.private, LoginCrypto.decode(pk)!!, token, pk, tk)
        assertArrayEquals(onPhone, onTv)
        val (iv, data) = LoginCrypto.seal(onPhone, "NYT-S=secret".toByteArray())
        assertEquals("NYT-S=secret", String(LoginCrypto.open(onTv, iv, data)!!))
        assertFalse("the value is not in the clear", data.contains("secret"))

        val withoutToken = LoginCrypto.sessionKey(tv.private, LoginCrypto.decode(pk)!!, "b".repeat(64), pk, tk)
        assertNull("another token, another key", LoginCrypto.open(withoutToken, iv, data))
        val changed = data.substring(0, 4) + (if (data[4] == 'A') 'B' else 'A') + data.substring(5)
        assertNull("one changed character and it is refused", LoginCrypto.open(onTv, iv, changed))
        assertNull(LoginCrypto.open(onTv, iv, "not base64 !"))
    }

    @Test
    fun theTvsSignatureAndThePhonesProofNeedTheToken() {
        val sig = LoginCrypto.tvSignature(token, "phoneKey", "tvKey")
        assertTrue(LoginCrypto.same(sig, LoginCrypto.tvSignature(token, "phoneKey", "tvKey")))
        assertFalse(LoginCrypto.same(sig, LoginCrypto.tvSignature("c".repeat(64), "phoneKey", "tvKey")))
        assertFalse("bound to both keys", LoginCrypto.same(sig, LoginCrypto.tvSignature(token, "phoneKey", "otherKey")))
        assertNotEquals(LoginCrypto.proof(token, "n1", "phone"), LoginCrypto.proof(token, "n2", "phone"))
        assertFalse("an empty one never matches", LoginCrypto.same("", ""))
        assertNull(LoginCrypto.decode("not a key"))
    }
}
