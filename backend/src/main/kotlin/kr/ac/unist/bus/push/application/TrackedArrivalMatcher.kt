package kr.ac.unist.bus.push.application

import kr.ac.unist.bus.arrival.domain.Arrival
import kr.ac.unist.bus.arrival.domain.ArrivalQueryResult
import kr.ac.unist.bus.config.PushProperties
import kr.ac.unist.bus.tracking.domain.TrackedArrival
import kr.ac.unist.bus.tracking.domain.TrackingSubscription
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class TrackedArrivalMatcher(
    private val properties: PushProperties,
) {
    fun match(
        subscription: TrackingSubscription,
        result: ArrivalQueryResult,
    ): TrackedArrival? {
        val exact = result.batch.arrivals.firstOrNull { it.tripId == subscription.tripId }
        val referenceEta = referenceEta(subscription)
        if (exact == null && !result.serverTime.isBefore(referenceEta)) {
            return null
        }
        val arrival = exact ?: result.batch.arrivals
            .asSequence()
            .filter { it.routeId == subscription.routeId }
            .minByOrNull {
                Duration.between(referenceEta, it.estimatedArrivalAt).abs()
            }
            ?.takeIf {
                Duration.between(referenceEta, it.estimatedArrivalAt).abs() <=
                    properties.matchWindow
            }
        return arrival?.toTracked(result)
    }

    fun isMissingAfterEta(
        subscription: TrackingSubscription,
        now: java.time.Instant,
    ): Boolean = !now.isBefore(referenceEta(subscription))

    private fun referenceEta(subscription: TrackingSubscription) =
        subscription.lastMatchedArrival?.estimatedArrivalAt ?: subscription.lastDeliveredEta

    private fun Arrival.toTracked(result: ArrivalQueryResult) =
        TrackedArrival(
            tripId = tripId,
            routeId = routeId,
            routeName = routeName,
            stopId = result.batch.stop.id,
            stopName = result.batch.stop.name,
            destination = destination,
            estimatedArrivalAt = estimatedArrivalAt,
            observedAt = observedAt,
            staleAt = staleAt,
            status = status,
            freshness = result.freshness,
        )
}
