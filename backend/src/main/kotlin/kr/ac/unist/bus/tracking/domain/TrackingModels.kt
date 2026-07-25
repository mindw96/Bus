package kr.ac.unist.bus.tracking.domain

import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.arrival.domain.Freshness
import kr.ac.unist.bus.tracking.security.EncryptedPushTarget
import java.time.Instant
import java.util.UUID

enum class TrackingPlatform {
    ANDROID_FCM,
    IOS_LIVE_ACTIVITY,
}

enum class PushEnvironment {
    DEVELOPMENT,
    PRODUCTION,
}

enum class TrackingState {
    ACTIVE,
    ENDED,
    EXPIRED,
}

data class TrackedArrival(
    val tripId: String,
    val routeId: String,
    val routeName: String,
    val stopId: String,
    val stopName: String,
    val destination: String,
    val estimatedArrivalAt: Instant,
    val observedAt: Instant,
    val staleAt: Instant,
    val status: ArrivalStatus,
    val freshness: Freshness,
)

data class TrackingSubscription(
    val trackingSessionId: UUID,
    val installationIdentifier: String,
    val platform: TrackingPlatform,
    val pushTarget: EncryptedPushTarget,
    val pushEnvironment: PushEnvironment,
    val tripId: String,
    val routeId: String,
    val routeName: String,
    val stopId: String,
    val initialEstimatedArrivalAt: Instant,
    val expiresAt: Instant,
    val state: TrackingState,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastMatchedArrival: TrackedArrival?,
    val lastDeliveredEta: Instant,
    val lastDeliveredStatus: ArrivalStatus?,
    val lastDeliveredFreshness: Freshness?,
    val lastDeliveredAt: Instant? = null,
    val deliveryClaimId: UUID? = null,
    val deliveryClaimUntil: Instant? = null,
    val revision: Long = 0,
) {
    override fun toString(): String =
        "TrackingSubscription(session=$trackingSessionId, platform=$platform, " +
            "targetHash=${pushTarget.hash.take(12)}, state=$state)"
}
