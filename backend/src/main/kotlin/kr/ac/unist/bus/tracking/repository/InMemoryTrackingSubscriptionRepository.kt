package kr.ac.unist.bus.tracking.repository

import kr.ac.unist.bus.tracking.domain.TrackingState
import kr.ac.unist.bus.tracking.domain.TrackingSubscription
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Repository
@ConditionalOnProperty(
    prefix = "unist-bus.tracking",
    name = ["storage"],
    havingValue = "memory",
    matchIfMissing = true,
)
class InMemoryTrackingSubscriptionRepository : TrackingSubscriptionRepository {
    private val subscriptions = ConcurrentHashMap<UUID, TrackingSubscription>()

    override fun findById(trackingSessionId: UUID): TrackingSubscription? =
        subscriptions[trackingSessionId]

    override fun findAllActive(): List<TrackingSubscription> =
        subscriptions.values.filter { it.state == TrackingState.ACTIVE }

    override fun mutate(
        trackingSessionId: UUID,
        mutation: (TrackingSubscription?) -> TrackingSubscription?,
    ): TrackingSubscription? =
        synchronized(subscriptions) {
            val current = subscriptions[trackingSessionId]
            val proposed = mutation(current) ?: return@synchronized null
            val stored = proposed.copy(revision = (current?.revision ?: -1) + 1)
            subscriptions[trackingSessionId] = stored
            stored
        }

    override fun compareAndMutate(
        trackingSessionId: UUID,
        expectedRevision: Long,
        mutation: (TrackingSubscription) -> TrackingSubscription?,
    ): TrackingSubscription? =
        synchronized(subscriptions) {
            val current = subscriptions[trackingSessionId]
                ?: return@synchronized null
            if (current.revision != expectedRevision) {
                return@synchronized null
            }
            val proposed = mutation(current) ?: return@synchronized null
            val stored = proposed.copy(revision = current.revision + 1)
            subscriptions[trackingSessionId] = stored
            stored
        }
}
