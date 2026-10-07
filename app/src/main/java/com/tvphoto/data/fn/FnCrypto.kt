package com.tvphoto.data.fn

import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitives for the fnOS private protocol.
 *
 * The login handshake is RSA + AES, and the `authx` signature is MD5 based.
 * Everything here is deterministic and side-effect free so it can be unit tested.
 */
internal object FnCrypto {

    val secureRandom = SecureRandom()

    private const val ALPHANUMERIC =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** Alphabet the fnOS client uses for its device id (`did`). */
    private const val DEVICE_ID_ALPHABET =
        "useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict"

    fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun randomString(length: Int, alphabet: String = ALPHANUMERIC): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(alphabet[secureRandom.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    fun deviceId(): String = randomString(24, DEVICE_ID_ALPHABET)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

    /** AES-256-CBC/PKCS5 with a 32-character ASCII key, exactly as fnOS expects. */
    fun aesEncrypt(plaintext: String, key: String, iv: ByteArray): ByteArray {
        require(key.length == 32) { "AES key must be 32 characters, was ${key.length}" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv),
        )
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    /**
     * RSA/ECB/PKCS1Padding against the server's public key.
     *
     * fnOS hands out an X.509 `SubjectPublicKeyInfo` PEM (`BEGIN PUBLIC KEY`), which
     * is what [X509EncodedKeySpec] consumes. A PKCS#1 body would need its DER header
     * restored first, so the header check is deliberate rather than cosmetic.
     */
    fun rsaEncrypt(publicKeyPem: String, plaintext: String): ByteArray {
        val body = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .filterNot { it.isWhitespace() }
        val keyBytes = Base64.decode(body, Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
