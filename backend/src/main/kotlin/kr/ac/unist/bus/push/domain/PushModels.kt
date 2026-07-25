package kr.ac.unist.bus.push.domain

import kr.ac.unist.bus.tracking.domain.TrackedArrival
import java.time.Instant
import java.util.UUID

enum class PushMessageType {
    ARRIVAL_UPDATE,
    TRACKING_END,
}

enum class TrackingEndReason {
    EXPIRED,
    ARRIVED,
    CANCELLED,
}

data class PushMessage(
    val type: PushMessageType,
    val trackingSessionId: UUID,
    val arrival: TrackedArrival,
    val timestamp: Instant,
    val expiresAt: Instant,
    val reason: TrackingEndReason? = null,
) {
    fun androidData(): Map<String, String> = buildMap {
        put("schemaVersion", "1")
        put("type", type.name)
        put("trackingSessionId", trackingSessionId.toString())
        put("tripId", arrival.tripId)
        put("routeId", arrival.routeId)
        put("routeName", arrival.routeName)
        put("stopId", arrival.stopId)
        put("stopName", arrival.stopName)
        put("destination", arrival.destination)
        put("estimatedArrivalAt", arrival.estimatedArrivalAt.toString())
        put("observedAt", arrival.observedAt.toString())
        put("staleAt", arrival.staleAt.toString())
        put("status", arrival.status.name)
        reason?.let { put("reason", it.name) }
    }
}

sealed interface PushDecision {
    data object None : PushDecision
    data object Update : PushDecision
    data class End(val reason: TrackingEndReason) : PushDecision
}
