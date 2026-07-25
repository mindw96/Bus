package kr.ac.unist.bus.push.application

import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.config.PushProperties
import kr.ac.unist.bus.push.domain.PushDecision
import kr.ac.unist.bus.push.domain.TrackingEndReason
import kr.ac.unist.bus.tracking.domain.TrackedArrival
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import kr.ac.unist.bus.tracking.domain.TrackingSubscription
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class PushDecisionPolicy(
    private val properties: PushProperties,
) {
    fun decide(
        subscription: TrackingSubscription,
        arrival: TrackedArrival,
        now: Instant,
    ): PushDecision {
        if (!now.isBefore(subscription.expiresAt)) {
            return PushDecision.End(TrackingEndReason.EXPIRED)
        }
        when (arrival.status) {
            ArrivalStatus.ARRIVED -> return PushDecision.End(TrackingEndReason.ARRIVED)
            ArrivalStatus.CANCELLED -> return PushDecision.End(TrackingEndReason.CANCELLED)
            else -> Unit
        }

        val etaChanged = Duration.between(
            subscription.lastDeliveredEta,
            arrival.estimatedArrivalAt,
        ).abs() >= properties.etaChangeThreshold
        val statusChanged = subscription.lastDeliveredStatus != arrival.status
        val freshnessChanged = subscription.lastDeliveredFreshness != arrival.freshness
        val heartbeatDue =
            subscription.platform == TrackingPlatform.IOS_LIVE_ACTIVITY &&
                (
                    subscription.lastDeliveredAt == null ||
                        !now.isBefore(
                            subscription.lastDeliveredAt.plus(properties.iosHeartbeatInterval),
                        )
                    )
        return if (etaChanged || statusChanged || freshnessChanged || heartbeatDue) {
            PushDecision.Update
        } else {
            PushDecision.None
        }
    }
}
