package kr.ac.unist.bus.push.application

import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.arrival.domain.Freshness
import kr.ac.unist.bus.config.PushProperties
import kr.ac.unist.bus.push.domain.PushDecision
import kr.ac.unist.bus.push.domain.TrackingEndReason
import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackedArrival
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import kr.ac.unist.bus.tracking.domain.TrackingState
import kr.ac.unist.bus.tracking.domain.TrackingSubscription
import kr.ac.unist.bus.tracking.security.EncryptedPushTarget
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class PushDecisionPolicyTest {
    private val now = Instant.parse("2026-07-24T09:00:00Z")
    private val policy = PushDecisionPolicy(
        PushProperties(etaChangeThreshold = Duration.ofSeconds(60)),
    )

    @Test
    fun `does not send for a 59 second ETA change`() {
        val subscription = subscription()
        val arrival = arrival(eta = subscription.lastDeliveredEta.plusSeconds(59))

        assertEquals(PushDecision.None, policy.decide(subscription, arrival, now))
    }

    @Test
    fun `sends at the 60 second ETA boundary`() {
        val subscription = subscription()
        val arrival = arrival(eta = subscription.lastDeliveredEta.plusSeconds(60))

        assertEquals(PushDecision.Update, policy.decide(subscription, arrival, now))
    }

    @Test
    fun `uses the last delivered ETA so small changes accumulate`() {
        val subscription = subscription().copy(
            lastMatchedArrival = arrival(eta = now.plusSeconds(10 * 60 + 30)),
        )
        val arrival = arrival(eta = now.plusSeconds(11 * 60))

        assertEquals(PushDecision.Update, policy.decide(subscription, arrival, now))
    }

    @Test
    fun `status and freshness changes send regardless of ETA`() {
        val subscription = subscription()

        assertEquals(
            PushDecision.Update,
            policy.decide(
                subscription,
                arrival(
                    eta = subscription.lastDeliveredEta,
                    status = ArrivalStatus.DELAYED,
                ),
                now,
            ),
        )
        assertEquals(
            PushDecision.Update,
            policy.decide(
                subscription,
                arrival(
                    eta = subscription.lastDeliveredEta,
                    freshness = Freshness.STALE,
                ),
                now,
            ),
        )
    }

    @Test
    fun `terminal and expired subscriptions end`() {
        val subscription = subscription()

        assertEquals(
            PushDecision.End(TrackingEndReason.ARRIVED),
            policy.decide(
                subscription,
                arrival(status = ArrivalStatus.ARRIVED),
                now,
            ),
        )
        assertEquals(
            PushDecision.End(TrackingEndReason.EXPIRED),
            policy.decide(
                subscription.copy(expiresAt = now),
                arrival(),
                now,
            ),
        )
    }

    @Test
    fun `iOS sends a heartbeat only when the 60 second interval is due`() {
        val beforeBoundary = subscription(
            platform = TrackingPlatform.IOS_LIVE_ACTIVITY,
            lastDeliveredAt = now.minusSeconds(59),
        )
        val atBoundary = beforeBoundary.copy(lastDeliveredAt = now.minusSeconds(60))
        val stableArrival = arrival(eta = beforeBoundary.lastDeliveredEta)

        assertEquals(
            PushDecision.None,
            policy.decide(beforeBoundary, stableArrival, now),
        )
        assertEquals(
            PushDecision.Update,
            policy.decide(atBoundary, stableArrival, now),
        )
    }

    private fun subscription(
        platform: TrackingPlatform = TrackingPlatform.ANDROID_FCM,
        lastDeliveredAt: Instant? = null,
    ) = TrackingSubscription(
        trackingSessionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        installationIdentifier = "hmac-sha256:example",
        platform = platform,
        pushTarget = EncryptedPushTarget("ciphertext", "hash"),
        pushEnvironment = PushEnvironment.DEVELOPMENT,
        tripId = "trip-713",
        routeId = "route-713",
        routeName = "713",
        stopId = "40233",
        initialEstimatedArrivalAt = now.plusSeconds(10 * 60),
        expiresAt = now.plusSeconds(2 * 60 * 60),
        state = TrackingState.ACTIVE,
        createdAt = now.minusSeconds(30),
        updatedAt = now.minusSeconds(30),
        lastMatchedArrival = null,
        lastDeliveredEta = now.plusSeconds(10 * 60),
        lastDeliveredStatus = ArrivalStatus.APPROACHING,
        lastDeliveredFreshness = Freshness.FRESH,
        lastDeliveredAt = lastDeliveredAt,
    )

    private fun arrival(
        eta: Instant = now.plusSeconds(10 * 60),
        status: ArrivalStatus = ArrivalStatus.APPROACHING,
        freshness: Freshness = Freshness.FRESH,
    ) = TrackedArrival(
        tripId = "trip-713",
        routeId = "route-713",
        routeName = "713",
        stopId = "40233",
        stopName = "울산과학기술원(기점)",
        destination = "덕하 차고지",
        estimatedArrivalAt = eta,
        observedAt = now,
        staleAt = now.plusSeconds(2 * 60),
        status = status,
        freshness = freshness,
    )
}
