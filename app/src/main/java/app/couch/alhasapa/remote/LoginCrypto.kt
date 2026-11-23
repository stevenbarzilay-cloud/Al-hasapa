package app.couch.alhasapa.remote

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The crypto of the sign-in handoff (and of the phone's proof that it was paired), with the platform's own primitives only.
 *
 * For each handoff both sides make a fresh P-256 key pair and agree on a secret (ECDH): someone listening on the home network sees the
 * two public keys and nothing else. The TV signs its public key with the pairing token (HMAC-SHA256), so the phone knows it is talking
 * to the TV it paired with; the key that encrypts the logins comes from the agreed secret AND the token (HKDF-SHA256), so only that TV
 * can open them; and AES-256-GCM refuses anything that was changed on the way.
 */
object LoginCrypto {
    private val random = SecureRandom()
    private const val GCM_TAG_BITS = 128
    private val INFO = "couch-logins-v1".toByteArray()
    private val AAD = "couch-logins".toByteArray()

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun encode(k: PublicKey): String = Base64.getEncoder().encodeToString(k.encoded)

    /** Null for anything that is not a P-256 public key. */
    fun decode(s: String): PublicKey? = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(s)))
    }.getOrNull()

    /** HMAC-SHA256 of the [parts] (joined by new lines) under the pairing [token], as hex. */
    fun mac(token: String, vararg parts: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(token.toByteArray(), "HmacSHA256"))
        return m.doFinal(parts.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Compares two MACs without telling, by how long it takes, how much of them matched. */
    fun same(a: String, b: String): Boolean = a.isNotEmpty() && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** The phone's proof that it holds the pairing [token], for the TV's [nonce] (the token itself never goes over the network). */
    fun proof(token: String, nonce: String, phone: String): String = mac(token, "hello", nonce, phone)

    /** The TV's signature of its key for this handoff, over both keys. */
    fun tvSignature(token: String, phoneKey: String, tvKey: String): String = mac(token, "tv-key", phoneKey, tvKey)

    /** The AES key of one handoff: the agreed secret, salted with the pairing token, bound to both public keys. */
    fun sessionKey(own: PrivateKey, other: PublicKey, token: String, phoneKey: String, tvKey: String): ByteArray {
        val secret = KeyAgreement.getInstance("ECDH").run { init(own); doPhase(other, true); generateSecret() }
        return hkdf(secret, token.toByteArray(), INFO + phoneKey.toByteArray() + tvKey.toByteArray(), 32)
    }

    /** (iv, data), both Base64. */
    fun seal(key: ByteArray, plain: ByteArray): Pair<String, String> {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        c.updateAAD(AAD)
        val b64 = Base64.getEncoder()
        return b64.encodeToString(iv) to b64.encodeToString(c.doFinal(plain))
    }

    /** The plain bytes, or null if anything about them is wrong (another key, a changed byte, not Base64). */
    fun open(key: ByteArray, iv: String, data: String): ByteArray? = runCatching {
        val d = Base64.getDecoder()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, d.decode(iv)))
        c.updateAAD(AAD)
        c.doFinal(d.decode(data))
    }.getOrNull()

    /** RFC 5869, extract and expand. */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract, "HmacSHA256")) }
        val out = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            expand.update(block); expand.update(info); expand.update(i.toByte())
            block = expand.doFinal()
            out.write(block)
            i++
        }
        return out.toByteArray().copyOf(length)
    }
}
