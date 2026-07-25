package kr.ac.unist.bus.push.application

import kr.ac.unist.bus.arrival.domain.Arrival
import kr.ac.unist.bus.arrival.domain.ArrivalBatch
import kr.ac.unist.bus.arrival.domain.ArrivalQueryResult
import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.arrival.domain.Freshness
import kr.ac.unist.bus.config.PushProperties
import kr.ac.unist.bus.stop.SupportedStop
import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import kr.ac.unist.bus.tracking.domain.TrackingState
import kr.ac.unist.bus.tracking.domain.TrackingSubscription
import kr.ac.unist.bus.tracking.security.EncryptedPushTarget
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackedArrivalMatcherTest {
    private val now = Instant.parse("2026-07-24T09:00:00Z")
    private val matcher = TrackedArrivalMatcher(
        PushProperties(matchWindow = Duration.ofMinutes(3)),
    )

    @Test
    fun `falls back to the same route and closest ETA when TAGO trip bucket changes`() {
        val result = result(
            arrival("new-bucket", "route-713", now.plusSeconds(11 * 60)),
            arrival("other-route", "route-743", now.plusSeconds(10 * 60 + 5)),
            arrival("later-same-route", "route-713", now.plusSeconds(14 * 60)),
        )

        val matched = matcher.match(subscription(), result)

        assertEquals("new-bucket", matched?.tripId)
        assertEquals("40233", matched?.stopId)
    }

    @Test
    fun `does not attach a different bus outside the match window`() {
        val result = result(
            arrival("much-later", "route-713", now.plusSeconds(30 * 60)),
        )

        assertNull(matcher.match(subscription(), result))
    }

    @Test
    fun `does not attach the next same-route bus after the tracked ETA`() {
        val subscription = subscription()
        val result = result(
            arrival("next-bus", "route-713", now.plusSeconds(11 * 60)),
            serverTime = subscription.lastDeliveredEta,
        )

        assertNull(matcher.match(subscription, result))
        assertTrue(matcher.isMissingAfterEta(subscription, result.serverTime))
    }

    private fun subscription() = TrackingSubscription(
        trackingSessionId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        installationIdentifier = "hmac-sha256:example",
        platform = TrackingPlatform.ANDROID_FCM,
        pushTarget = EncryptedPushTarget("ciphertext", "hash"),
        pushEnvironment = PushEnvironment.DEVELOPMENT,
        tripId = "old-bucket",
        routeId = "route-713",
        routeName = "713",
        stopId = "40233",
        initialEstimatedArrivalAt = now.plusSeconds(10 * 60),
        expiresAt = now.plusSeconds(2 * 60 * 60),
        state = TrackingState.ACTIVE,
        createdAt = now,
        updatedAt = now,
        lastMatchedArrival = null,
        lastDeliveredEta = now.plusSeconds(10 * 60),
        lastDeliveredStatus = ArrivalStatus.APPROACHING,
        lastDeliveredFreshness = Freshness.FRESH,
    )

    private fun result(
        vararg arrivals: Arrival,
        serverTime: Instant = now,
    ) = ArrivalQueryResult(
        serverTime = serverTime,
        freshness = Freshness.FRESH,
        batch = ArrivalBatch(
            stop = SupportedStop("40233", "울산과학기술원(기점)"),
            arrivals = arrivals.toList(),
            observedAt = now,
            staleAt = now.plusSeconds(2 * 60),
        ),
    )

    private fun arrival(
        tripId: String,
        routeId: String,
        eta: Instant,
    ) = Arrival(
        tripId = tripId,
        routeId = routeId,
        routeName = routeId.removePrefix("route-"),
        destination = "덕하 차고지",
        scheduledArrivalAt = eta,
        estimatedArrivalAt = eta,
        observedAt = now,
        staleAt = now.plusSeconds(2 * 60),
        status = ArrivalStatus.APPROACHING,
    )
}
