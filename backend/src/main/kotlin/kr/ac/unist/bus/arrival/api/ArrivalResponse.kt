package kr.ac.unist.bus.arrival.api

import kr.ac.unist.bus.arrival.domain.Arrival
import kr.ac.unist.bus.arrival.domain.ArrivalQueryResult
import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.arrival.domain.Freshness
import java.time.Instant

data class ArrivalResponse(
    val serverTime: Instant,
    val freshness: Freshness,
    val stop: StopResponse,
    val arrivals: List<ArrivalItemResponse>,
) {
    companion object {
        fun from(result: ArrivalQueryResult): ArrivalResponse =
            ArrivalResponse(
                serverTime = result.serverTime,
                freshness = result.freshness,
                stop = StopResponse(
                    id = result.batch.stop.id,
                    name = result.batch.stop.name,
                ),
                arrivals = result.batch.arrivals.map(ArrivalItemResponse::from),
            )
    }
}

data class StopResponse(
    val id: String,
    val name: String,
)

data class ArrivalItemResponse(
    val tripId: String,
    val routeId: String,
    val routeName: String,
    val destination: String,
    val scheduledArrivalAt: Instant,
    val estimatedArrivalAt: Instant,
    val observedAt: Instant,
    val staleAt: Instant,
    val status: ArrivalStatus,
) {
    companion object {
        fun from(arrival: Arrival): ArrivalItemResponse =
            ArrivalItemResponse(
                tripId = arrival.tripId,
                routeId = arrival.routeId,
                routeName = arrival.routeName,
                destination = arrival.destination,
                scheduledArrivalAt = arrival.scheduledArrivalAt,
                estimatedArrivalAt = arrival.estimatedArrivalAt,
                observedAt = arrival.observedAt,
                staleAt = arrival.staleAt,
                status = arrival.status,
            )
    }
}
