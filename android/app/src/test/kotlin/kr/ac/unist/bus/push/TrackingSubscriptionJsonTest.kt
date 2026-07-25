package kr.ac.unist.bus.push

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingSubscriptionJsonTest {
    @Test
    fun encodesExactBackendContractWithoutLoggingOrExtraFields() {
        val request = TrackingSubscriptionRequest(
            trackingSessionId = "session-1",
            installationId = "fid-private-value",
            pushEnvironment = PushEnvironment.DEVELOPMENT,
            tripId = "trip-713",
            routeId = "route-713",
            routeName = "713",
            stopId = "40233",
            estimatedArrivalAt = Instant.parse("2026-07-24T05:30:00Z"),
            expiresAt = Instant.parse("2026-07-24T05:45:00Z"),
        )

        val json = JSONObject(TrackingSubscriptionJson.encode(request))

        assertEquals(
            setOf(
                "installationId",
                "platform",
                "pushTarget",
                "pushEnvironment",
                "tripId",
                "routeId",
                "routeName",
                "stopId",
                "estimatedArrivalAt",
                "expiresAt",
            ),
            json.keys().asSequence().toSet(),
        )
        assertEquals("ANDROID_FCM", json.getString("platform"))
        assertEquals("fid-private-value", json.getString("installationId"))
        assertEquals("fid-private-value", json.getString("pushTarget"))
        assertEquals("DEVELOPMENT", json.getString("pushEnvironment"))
    }

    @Test
    fun decodesSubscriptionResponse() {
        val response = TrackingSubscriptionJson.decodeResponse(
            """
            {
              "trackingSessionId": "session-1",
              "state": "ACTIVE",
              "updatedAt": "2026-07-24T05:22:00Z",
              "expiresAt": "2026-07-24T05:45:00Z"
            }
            """.trimIndent(),
        )

        assertEquals("session-1", response.trackingSessionId)
        assertEquals("ACTIVE", response.state)
        assertEquals(Instant.parse("2026-07-24T05:45:00Z"), response.expiresAt)
    }
}
