package kr.ac.unist.bus.push.apns

import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.arrival.domain.Freshness
import kr.ac.unist.bus.push.domain.PushMessage
import kr.ac.unist.bus.push.domain.PushMessageType
import kr.ac.unist.bus.tracking.domain.TrackedArrival
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ApnsPayloadFactoryTest {
    @Test
    fun `uses unix epoch content state fields required by ActivityKit`() {
        val now = Instant.parse("2026-07-24T09:00:00Z")
        val message = PushMessage(
            type = PushMessageType.ARRIVAL_UPDATE,
            trackingSessionId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
            arrival = TrackedArrival(
                tripId = "trip-713",
                routeId = "route-713",
                routeName = "713",
                stopId = "40233",
                stopName = "울산과학기술원(기점)",
                destination = "덕하 차고지",
                estimatedArrivalAt = now.plusSeconds(7 * 60),
                observedAt = now,
                staleAt = now.plusSeconds(2 * 60),
                status = ArrivalStatus.APPROACHING,
                freshness = Freshness.FRESH,
            ),
            timestamp = now,
            expiresAt = now.plusSeconds(60 * 60),
        )

        val aps = ApnsPayloadFactory().create(message)["aps"] as Map<*, *>
        val state = aps["content-state"] as Map<*, *>

        assertEquals("update", aps["event"])
        assertEquals(now.epochSecond, aps["timestamp"])
        assertEquals(now.plusSeconds(7 * 60).epochSecond, state["estimatedArrivalEpochSeconds"])
        assertEquals(now.epochSecond, state["observedAtEpochSeconds"])
        assertEquals("APPROACHING", state["status"])
        assertNotNull(aps["stale-date"])
    }
}
