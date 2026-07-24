package kr.ac.unist.bus.model

import java.time.Duration
import java.time.Instant

enum class ArrivalStatus {
    SCHEDULED,
    APPROACHING,
    ARRIVING,
    DELAYED,
    ARRIVED,
    CANCELLED,
    UNKNOWN,
}

enum class CountdownKind(
    val eventName: String,
) {
    ARRIVAL("도착"),
    DEPARTURE("출발"),
}

data class BusStop(
    val id: String,
    val name: String,
)

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

data class StopArrival(
    val stop: BusStop,
    val arrival: Arrival,
) {
    val countdownKind: CountdownKind
        get() = if (stop.id == "40233") {
            CountdownKind.DEPARTURE
        } else {
            CountdownKind.ARRIVAL
        }
}

data class ArrivalSnapshot(
    val arrivals: List<StopArrival>,
    val generatedAt: Instant,
)

object ArrivalMerger {
    fun earliestByRoute(items: List<StopArrival>): List<StopArrival> =
        items
            .groupBy { it.arrival.routeName }
            .values
            .mapNotNull { routeItems ->
                routeItems.minByOrNull { it.arrival.estimatedArrivalAt }
            }
            .sortedBy { it.arrival.estimatedArrivalAt }
}

object ArrivalText {
    fun remainingTime(
        estimatedAt: Instant,
        now: Instant,
    ): String {
        val totalSeconds = Duration.between(now, estimatedAt).seconds.coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes}분 ${seconds}초"
    }

    fun status(
        item: StopArrival,
        now: Instant,
    ): String {
        val arrival = item.arrival
        val eventName = item.countdownKind.eventName

        return when {
            arrival.status == ArrivalStatus.CANCELLED -> "운행 취소"
            arrival.status == ArrivalStatus.ARRIVED -> if (item.countdownKind == CountdownKind.DEPARTURE) {
                "출발 완료"
            } else {
                "도착"
            }
            now >= arrival.staleAt -> "데이터 지연"
            arrival.status == ArrivalStatus.ARRIVING || now >= arrival.estimatedArrivalAt -> "곧 $eventName"
            arrival.status == ArrivalStatus.UNKNOWN -> "정보 확인 중"
            else -> "남은 시간: ${remainingTime(arrival.estimatedArrivalAt, now)}"
        }
    }
}
