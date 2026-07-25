package kr.ac.unist.bus.tracking.api

import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import kr.ac.unist.bus.tracking.repository.TrackingSubscriptionRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "unist-bus.tracking.storage=memory",
        "unist-bus.push.enabled=false",
    ],
)
@AutoConfigureMockMvc
class TrackingSubscriptionApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var repository: TrackingSubscriptionRepository

    @Test
    fun `upserts without returning the sensitive push target and caps expiry`() {
        val sessionId = UUID.randomUUID()
        val now = Instant.now()
        val installationId = "c1234567890_example_FID"

        mockMvc.put("/api/v1/tracking-subscriptions/$sessionId") {
            contentType = MediaType.APPLICATION_JSON
            content = requestBody(
                installationId = installationId,
                estimatedArrivalAt = now.plusSeconds(10 * 60),
                expiresAt = now.plusSeconds(4 * 60 * 60),
            )
        }.andExpect {
            status { isOk() }
            jsonPath("$.trackingSessionId") { value(sessionId.toString()) }
            jsonPath("$.state") { value("ACTIVE") }
            jsonPath("$.expiresAt") { exists() }
            jsonPath("$.pushTarget") { doesNotExist() }
            jsonPath("$.installationId") { doesNotExist() }
        }

        mockMvc.put("/api/v1/tracking-subscriptions/$sessionId") {
            contentType = MediaType.APPLICATION_JSON
            content = requestBody(
                installationId = installationId,
                estimatedArrivalAt = now.plusSeconds(10 * 60),
                expiresAt = now.plusSeconds(4 * 60 * 60),
            )
        }.andExpect {
            status { isOk() }
            jsonPath("$.state") { value("ACTIVE") }
        }

        val stored = assertNotNull(repository.findById(sessionId))
        assertNotEquals(installationId, stored.installationIdentifier)
        assertTrue(stored.installationIdentifier.startsWith("hmac-sha256:"))
    }

    @Test
    fun `rejects ownership changes and invalid ActivityKit token shapes`() {
        val sessionId = UUID.randomUUID()
        val now = Instant.now()

        mockMvc.put("/api/v1/tracking-subscriptions/$sessionId") {
            contentType = MediaType.APPLICATION_JSON
            content = requestBody(
                installationId = "c1234567890_example_FID",
                estimatedArrivalAt = now.plusSeconds(10 * 60),
                expiresAt = now.plusSeconds(30 * 60),
            )
        }.andExpect {
            status { isOk() }
        }

        mockMvc.put("/api/v1/tracking-subscriptions/$sessionId") {
            contentType = MediaType.APPLICATION_JSON
            content = requestBody(
                installationId = "c9999999999_other_FID",
                estimatedArrivalAt = now.plusSeconds(11 * 60),
                expiresAt = now.plusSeconds(30 * 60),
            )
        }.andExpect {
            status { isConflict() }
        }

        mockMvc.put("/api/v1/tracking-subscriptions/${UUID.randomUUID()}") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "installationId": "00000000-0000-0000-0000-000000000001",
                  "platform": "IOS_LIVE_ACTIVITY",
                  "pushTarget": "../not-a-live-activity-token",
                  "pushEnvironment": "DEVELOPMENT",
                  "tripId": "trip-713",
                  "routeId": "route-713",
                  "routeName": "713",
                  "stopId": "40233",
                  "estimatedArrivalAt": "${now.plusSeconds(10 * 60)}",
                  "expiresAt": "${now.plusSeconds(30 * 60)}"
                }
                """.trimIndent()
        }.andExpect {
            status { isUnprocessableContent() }
        }
    }

    @Test
    fun `delete is idempotent and disabled worker is hidden`() {
        val sessionId = UUID.randomUUID()

        repeat(2) {
            mockMvc.delete("/api/v1/tracking-subscriptions/$sessionId")
                .andExpect {
                    status { isNoContent() }
                }
        }

        mockMvc.post("/internal/v1/push/refresh")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `request and command strings redact mobile identifiers`() {
        val installationId = "c1234567890_sensitive_FID"
        val pushTarget = "c1234567890_sensitive_push_target"
        val request = UpsertTrackingSubscriptionRequest(
            installationId = installationId,
            platform = TrackingPlatform.ANDROID_FCM,
            pushTarget = pushTarget,
            pushEnvironment = PushEnvironment.DEVELOPMENT,
            tripId = "trip-713",
            routeId = "route-713",
            routeName = "713",
            stopId = "40233",
            estimatedArrivalAt = Instant.now().plusSeconds(10 * 60),
            expiresAt = Instant.now().plusSeconds(30 * 60),
        )

        assertFalse(request.toString().contains(installationId))
        assertFalse(request.toString().contains(pushTarget))
        assertFalse(request.toCommand().toString().contains(installationId))
        assertFalse(request.toCommand().toString().contains(pushTarget))
    }

    private fun requestBody(
        installationId: String,
        estimatedArrivalAt: Instant,
        expiresAt: Instant,
    ): String =
        """
        {
          "installationId": "$installationId",
          "platform": "ANDROID_FCM",
          "pushTarget": "$installationId",
          "pushEnvironment": "DEVELOPMENT",
          "tripId": "trip-713",
          "routeId": "route-713",
          "routeName": "713",
          "stopId": "40233",
          "estimatedArrivalAt": "$estimatedArrivalAt",
          "expiresAt": "$expiresAt"
        }
        """.trimIndent()
}
