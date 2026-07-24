package kr.ac.unist.bus.arrival.domain

import kr.ac.unist.bus.stop.SupportedStop
import java.time.Instant

enum class ArrivalStatus {
    SCHEDULED,
    APPROACHING,
    ARRIVING,
    ARRIVED,
    DELAYED,
    CANCELLED,
    UNKNOWN,
}

enum class Freshness {
    FRESH,
    STALE,
}

data class Arrival(
    val tripId: String,
    val routeId: String,
    val routeName: String,
    val destination: String,
    val scheduledArrivalAt: Instant,
    val estimatedArrivalAt: Instant,
    val observedAt: Instant,
    val staleAt: Instant,
    val status: ArrivalStatus,
)

data class ArrivalBatch(
    val stop: SupportedStop,
    val arrivals: List<Arrival>,
    val observedAt: Instant,
    val staleAt: Instant,
)

data class ArrivalQueryResult(
    val serverTime: Instant,
    val freshness: Freshness,
    val batch: ArrivalBatch,
)
