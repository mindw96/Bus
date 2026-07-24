package kr.ac.unist.bus.data

import kr.ac.unist.bus.model.Arrival
import kr.ac.unist.bus.model.ArrivalMerger
import kr.ac.unist.bus.model.ArrivalSnapshot
import kr.ac.unist.bus.model.ArrivalStatus
import kr.ac.unist.bus.model.BusStop
import kr.ac.unist.bus.model.StopArrival
import java.time.Duration
import java.time.Instant

class FixtureArrivalRepository(
    private val fixtureStart: Instant = Instant.now(),
) : ArrivalRepository {
    override suspend fun fetch(): ArrivalSnapshot = load()

    fun load(now: Instant = fixtureStart): ArrivalSnapshot {
        val rawArrivals = fixtureRoutes.map { fixture ->
            val estimatedAt = now.plusSeconds(fixture.offsetSeconds)
            StopArrival(
                stop = fixture.stop,
                arrival = Arrival(
                    tripId = "fixture-${fixture.stop.id}-${fixture.routeName}",
                    routeId = "fixture-route-${fixture.routeName}",
                    routeName = fixture.routeName,
                    destination = fixture.destination,
                    scheduledArrivalAt = estimatedAt,
                    estimatedArrivalAt = estimatedAt,
                    observedAt = now,
                    staleAt = now.plus(Duration.ofMinutes(45)),
                    status = fixture.status,
                ),
            )
        }

        return ArrivalSnapshot(
            arrivals = ArrivalMerger.earliestByRoute(rawArrivals),
            generatedAt = now,
        )
    }

    private data class FixtureRoute(
        val stop: BusStop,
        val routeName: String,
        val destination: String,
        val offsetSeconds: Long,
        val status: ArrivalStatus,
    )

    private companion object {
        val MAIN_STOP = BusStop("40234", "울산과학기술원")
        val ORIGIN_STOP = BusStop("40233", "울산과학기술원(기점)")
        val TERMINUS_STOP = BusStop("40235", "울산과학기술원(종점)")

        val fixtureRoutes = listOf(
            FixtureRoute(MAIN_STOP, "513", "덕하 차고지", 5 * 60 + 12L, ArrivalStatus.APPROACHING),
            FixtureRoute(ORIGIN_STOP, "713", "덕하 차고지", 7 * 60 + 28L, ArrivalStatus.APPROACHING),
            FixtureRoute(ORIGIN_STOP, "743", "덕하 차고지", 12 * 60 + 40L, ArrivalStatus.SCHEDULED),
            FixtureRoute(ORIGIN_STOP, "753", "덕하 차고지", 16 * 60 + 15L, ArrivalStatus.DELAYED),
            FixtureRoute(ORIGIN_STOP, "1115", "덕하 차고지", 21 * 60 + 50L, ArrivalStatus.SCHEDULED),
            FixtureRoute(TERMINUS_STOP, "713", "UNIST", 29 * 60L, ArrivalStatus.SCHEDULED),
            FixtureRoute(TERMINUS_STOP, "743", "UNIST", 33 * 60L, ArrivalStatus.SCHEDULED),
            FixtureRoute(TERMINUS_STOP, "753", "UNIST", 37 * 60L, ArrivalStatus.SCHEDULED),
            FixtureRoute(TERMINUS_STOP, "1115", "UNIST", 42 * 60L, ArrivalStatus.SCHEDULED),
        )
    }
}
