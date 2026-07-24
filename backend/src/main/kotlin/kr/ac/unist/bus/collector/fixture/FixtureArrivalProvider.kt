package kr.ac.unist.bus.collector.fixture

import kr.ac.unist.bus.arrival.domain.Arrival
import kr.ac.unist.bus.arrival.domain.ArrivalBatch
import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.collector.ArrivalProvider
import kr.ac.unist.bus.config.ArrivalProperties
import kr.ac.unist.bus.stop.SupportedStop
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Component
@ConditionalOnProperty(
    prefix = "unist-bus.arrivals",
    name = ["mode"],
    havingValue = "fixture",
    matchIfMissing = true,
)
class FixtureArrivalProvider(
    private val properties: ArrivalProperties,
) : ArrivalProvider {
    override fun fetch(stop: SupportedStop, observedAt: Instant): ArrivalBatch {
        val arrivals = routesFor(stop.id).map { route ->
            val estimatedAt = nextOccurrence(observedAt, route.minuteInHour)
            Arrival(
                tripId = "fixture-${stop.id}-${route.name}-${estimatedAt.epochSecond}",
                routeId = "fixture-route-${route.name}",
                routeName = route.name,
                destination = route.destination,
                scheduledArrivalAt = estimatedAt.plus(Duration.ofMinutes(2)),
                estimatedArrivalAt = estimatedAt,
                observedAt = observedAt,
                staleAt = observedAt.plus(properties.staleAfter),
                status = route.status,
            )
        }

        return ArrivalBatch(
            stop = stop,
            arrivals = arrivals,
            observedAt = observedAt,
            staleAt = observedAt.plus(properties.staleAfter),
        )
    }

    private fun nextOccurrence(observedAt: Instant, minuteInHour: Int): Instant {
        val hour = ZonedDateTime.ofInstant(observedAt, SEOUL)
            .truncatedTo(ChronoUnit.HOURS)
        val candidate = hour.plusMinutes(minuteInHour.toLong())
        return (if (candidate.toInstant() > observedAt) candidate else candidate.plusHours(1)).toInstant()
    }

    private fun routesFor(stopId: String): List<FixtureRoute> =
        when (stopId) {
            "40234" -> listOf(
                FixtureRoute("513", "덕하 차고지", 6, ArrivalStatus.APPROACHING),
            )
            "40233" -> listOf(
                FixtureRoute("713", "덕하 차고지", 9, ArrivalStatus.APPROACHING),
                FixtureRoute("743", "덕하 차고지", 13, ArrivalStatus.SCHEDULED),
                FixtureRoute("753", "덕하 차고지", 17, ArrivalStatus.DELAYED),
                FixtureRoute("1115", "덕하 차고지", 22, ArrivalStatus.SCHEDULED),
            )
            "40235" -> listOf(
                FixtureRoute("713", "UNIST", 29, ArrivalStatus.SCHEDULED),
                FixtureRoute("743", "UNIST", 33, ArrivalStatus.SCHEDULED),
                FixtureRoute("753", "UNIST", 37, ArrivalStatus.SCHEDULED),
                FixtureRoute("1115", "UNIST", 42, ArrivalStatus.SCHEDULED),
            )
            else -> emptyList()
        }

    private data class FixtureRoute(
        val name: String,
        val destination: String,
        val minuteInHour: Int,
        val status: ArrivalStatus,
    )

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
