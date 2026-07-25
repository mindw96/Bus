package kr.ac.unist.bus.tracking.repository

import kr.ac.unist.bus.tracking.domain.TrackingSubscription
import java.util.UUID

interface TrackingSubscriptionRepository {
    fun findById(trackingSessionId: UUID): TrackingSubscription?
    fun findAllActive(): List<TrackingSubscription>

    fun mutate(
        trackingSessionId: UUID,
        mutation: (TrackingSubscription?) -> TrackingSubscription?,
    ): TrackingSubscription?

    fun compareAndMutate(
        trackingSessionId: UUID,
        expectedRevision: Long,
        mutation: (TrackingSubscription) -> TrackingSubscription?,
    ): TrackingSubscription?
}
