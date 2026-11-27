package app.couch.alhasapa.remote

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The pictures of a game the TV sends to the phone in touch mode (1.9.8) are sealed for that phone only: AES-256-GCM, under a key both
 * sides make from the pairing token (which never crosses the network) and this connection's own nonce (the TV's "hi"). Someone else on
 * the home network sees that pictures go, and nothing of what they show; a picture changed on the way is refused.
 */
object FrameCrypto {
    private val random = SecureRandom()
    private const val GCM_TAG_BITS = 128
    private val INFO = "couch-frames-v1".toByteArray()

    /** The connection's picture key: HKDF-SHA256 of the pairing [token], salted with the TV's [nonce] for this connection. */
    fun key(token: String, nonce: String): ByteArray = LoginCrypto.hkdf(token.toByteArray(), nonce.toByteArray(), INFO, 32)

    /** Picture [n], sealed: (iv, data), both Base64. The number is bound in, so a picture cannot pass for another. */
    fun seal(key: ByteArray, n: Int, plain: ByteArray): Pair<String, String> {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        c.updateAAD(aad(n))
        val b64 = Base64.getEncoder()
        return b64.encodeToString(iv) to b64.encodeToString(c.doFinal(plain))
    }

    /** The picture's bytes, or null if anything about them is wrong (another key, another number, a changed byte, not Base64). */
    fun open(key: ByteArray, n: Int, iv: String, data: String): ByteArray? = runCatching {
        val d = Base64.getDecoder()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, d.decode(iv)))
        c.updateAAD(aad(n))
        c.doFinal(d.decode(data))
    }.getOrNull()

    private fun aad(n: Int) = "couch-frame $n".toByteArray()
}
