package kr.ac.unist.bus.config

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PushConfigurationTest {
    @Test
    fun `enabled push rejects process-local memory storage`() {
        val validator = PushConfiguration().pushConfigurationValidator(
            tracking = TrackingProperties(storage = TrackingProperties.Storage.MEMORY),
            push = PushProperties(
                enabled = true,
                refreshKey = "worker-secret",
                firebase = PushProperties.Firebase(projectId = "project"),
                fcm = PushProperties.Fcm(enabled = true),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            validator.afterPropertiesSet()
        }
    }

    @Test
    fun `configuration strings redact credentials`() {
        val trackingSecret = "base64-encryption-secret"
        val refreshSecret = "worker-refresh-secret"
        val authSecret = "apns-private-key"
        val tracking = TrackingProperties(encryptionKeyBase64 = trackingSecret)
        val push = PushProperties(
            refreshKey = refreshSecret,
            apns = PushProperties.Apns(
                keyId = "key-id",
                teamId = "team-id",
                bundleId = "kr.ac.unist.bus",
                authKey = authSecret,
            ),
        )

        assertFalse(tracking.toString().contains(trackingSecret))
        assertFalse(push.toString().contains(refreshSecret))
        assertFalse(push.toString().contains(authSecret))
        assertFalse(push.toString().contains("key-id"))
        assertFalse(push.toString().contains("team-id"))
    }
}
