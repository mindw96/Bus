package kr.ac.unist.bus.push.fcm

import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.arrival.domain.Freshness
import kr.ac.unist.bus.push.domain.PushMessage
import kr.ac.unist.bus.push.domain.PushMessageType
import kr.ac.unist.bus.push.domain.TrackingEndReason
import kr.ac.unist.bus.tracking.domain.TrackedArrival
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class FcmPushGatewayTest {
    private val now = Instant.parse("2026-07-24T09:00:00Z")

    @Test
    fun `updates use remaining subscription lifetime`() {
        val message = message(
            type = PushMessageType.ARRIVAL_UPDATE,
            expiresAt = now.plus(Duration.ofHours(2)),
        )

        assertEquals(Duration.ofHours(2).toMillis(), message.fcmTtlMillis())
    }

    @Test
    fun `expired tracking end remains deliverable for fifteen minutes`() {
        val message = message(
            type = PushMessageType.TRACKING_END,
            expiresAt = now.minusSeconds(1),
            reason = TrackingEndReason.EXPIRED,
        )

        assertEquals(Duration.ofMinutes(15).toMillis(), message.fcmTtlMillis())
    }

    @Test
    fun `ttl is clamped to the FCM four week maximum`() {
        val message = message(
            type = PushMessageType.ARRIVAL_UPDATE,
            expiresAt = now.plus(Duration.ofDays(90)),
        )

        assertEquals(Duration.ofDays(28).toMillis(), message.fcmTtlMillis())
    }

    private fun message(
        type: PushMessageType,
        expiresAt: Instant,
        reason: TrackingEndReason? = null,
    ) = PushMessage(
        type = type,
        trackingSessionId = UUID.fromString("00000000-0000-0000-0000-000000000201"),
        arrival = TrackedArrival(
            tripId = "trip-713",
            routeId = "route-713",
            routeName = "713",
            stopId = "40233",
            stopName = "울산과학기술원(기점)",
            destination = "덕하 차고지",
            estimatedArrivalAt = now.plusSeconds(10 * 60),
            observedAt = now,
            staleAt = now.plusSeconds(2 * 60),
            status = ArrivalStatus.APPROACHING,
            freshness = Freshness.FRESH,
        ),
        timestamp = now,
        expiresAt = expiresAt,
        reason = reason,
    )
}
