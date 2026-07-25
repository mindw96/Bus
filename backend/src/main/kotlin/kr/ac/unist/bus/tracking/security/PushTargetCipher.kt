package kr.ac.unist.bus.tracking.security

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedPushTarget(
    val ciphertext: String,
    val hash: String,
) {
    override fun toString(): String = "EncryptedPushTarget(hash=${hash.take(12)})"
}

interface PushTargetCipher {
    fun encrypt(value: String): EncryptedPushTarget
    fun decrypt(value: EncryptedPushTarget): String
    fun installationIdentifier(value: String): String
    fun matchesInstallationIdentifier(storedIdentifier: String, value: String): Boolean
}

class AesGcmPushTargetCipher(
    key: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) : PushTargetCipher {
    private val secretKey = SecretKeySpec(key.copyOf(), "AES")
    private val installationIdentifierKey = deriveInstallationIdentifierKey(key)

    override fun encrypt(value: String): EncryptedPushTarget {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteBuffer.allocate(nonce.size + encrypted.size)
            .put(nonce)
            .put(encrypted)
            .array()
        return EncryptedPushTarget(
            ciphertext = Base64.getEncoder().encodeToString(combined),
            hash = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
    }

    override fun decrypt(value: EncryptedPushTarget): String {
        val combined = Base64.getDecoder().decode(value.ciphertext)
        require(combined.size > NONCE_BYTES) { "암호화된 push target 형식이 올바르지 않습니다." }
        val nonce = combined.copyOfRange(0, NONCE_BYTES)
        val encrypted = combined.copyOfRange(NONCE_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    override fun installationIdentifier(value: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(installationIdentifierKey, HMAC_ALGORITHM))
        val digest = mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return "$INSTALLATION_IDENTIFIER_PREFIX${HexFormat.of().formatHex(digest)}"
    }

    override fun matchesInstallationIdentifier(
        storedIdentifier: String,
        value: String,
    ): Boolean {
        val expected = if (storedIdentifier.startsWith(INSTALLATION_IDENTIFIER_PREFIX)) {
            installationIdentifier(value)
        } else {
            value
        }
        return MessageDigest.isEqual(
            storedIdentifier.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun deriveInstallationIdentifierKey(key: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key.copyOf(), HMAC_ALGORITHM))
        return mac.doFinal(INSTALLATION_IDENTIFIER_KEY_CONTEXT)
    }

    private companion object {
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val INSTALLATION_IDENTIFIER_PREFIX = "hmac-sha256:"
        val INSTALLATION_IDENTIFIER_KEY_CONTEXT =
            "unist-bus-installation-identifier-v1".toByteArray(StandardCharsets.UTF_8)
    }
}
