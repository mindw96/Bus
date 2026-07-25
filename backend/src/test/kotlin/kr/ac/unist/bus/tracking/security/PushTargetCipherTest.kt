package kr.ac.unist.bus.tracking.security

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PushTargetCipherTest {
    @Test
    fun `encrypts round trips without exposing the target in string output`() {
        val target = "c1234567890_private_FID"
        val cipher = AesGcmPushTargetCipher(ByteArray(32) { it.toByte() })

        val encrypted = cipher.encrypt(target)

        assertEquals(target, cipher.decrypt(encrypted))
        assertFalse(encrypted.ciphertext.contains(target))
        assertFalse(encrypted.toString().contains(target))
    }

    @Test
    fun `installation identifiers are deterministic keyed values without plaintext`() {
        val installationId = "c1234567890_private_FID"
        val firstCipher = AesGcmPushTargetCipher(ByteArray(32) { it.toByte() })
        val sameKeyCipher = AesGcmPushTargetCipher(ByteArray(32) { it.toByte() })
        val otherKeyCipher = AesGcmPushTargetCipher(ByteArray(32) { (it + 1).toByte() })

        val identifier = firstCipher.installationIdentifier(installationId)

        assertEquals(identifier, firstCipher.installationIdentifier(installationId))
        assertEquals(identifier, sameKeyCipher.installationIdentifier(installationId))
        assertTrue(firstCipher.matchesInstallationIdentifier(identifier, installationId))
        assertTrue(firstCipher.matchesInstallationIdentifier(installationId, installationId))
        assertFalse(firstCipher.matchesInstallationIdentifier(identifier, identifier))
        assertTrue(identifier.startsWith("hmac-sha256:"))
        assertFalse(identifier.contains(installationId))
        assertNotEquals(
            identifier,
            firstCipher.installationIdentifier("${installationId}_other"),
        )
        assertNotEquals(identifier, otherKeyCipher.installationIdentifier(installationId))
    }
}
