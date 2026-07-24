package kr.ac.unist.bus.data

import java.time.Instant
import kr.ac.unist.bus.model.CountdownKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureArrivalRepositoryTest {
    private val repository = FixtureArrivalRepository()
    private val now = Instant.parse("2026-07-24T00:00:00Z")

    @Test
    fun fixtureShowsExactlyFiveSupportedRoutes() {
        val snapshot = repository.load(now)

        assertEquals(
            listOf("513", "713", "743", "753", "1115"),
            snapshot.arrivals.map { it.arrival.routeName },
        )
    }

    @Test
    fun duplicatedRoutesAreMergedToEarliestUnistStop() {
        val snapshot = repository.load(now)
        val route713 = snapshot.arrivals.single { it.arrival.routeName == "713" }

        assertEquals("40233", route713.stop.id)
        assertEquals(CountdownKind.DEPARTURE, route713.countdownKind)
        assertEquals(now.plusSeconds(7 * 60 + 28L), route713.arrival.estimatedArrivalAt)
    }

    @Test
    fun allThreeMainStopsParticipateBeforeMerge() {
        val snapshot = repository.load(now)

        assertTrue(snapshot.arrivals.any { it.stop.id == "40234" })
        assertTrue(snapshot.arrivals.any { it.stop.id == "40233" })
        assertEquals(5, snapshot.arrivals.size)
    }

    @Test
    fun repeatedRefreshPreservesAbsoluteEtaAndTripIdentity() {
        val stableRepository = FixtureArrivalRepository(fixtureStart = now)
        val first = stableRepository.load()
        val second = stableRepository.load()

        assertEquals(
            first.arrivals.map { it.arrival.tripId },
            second.arrivals.map { it.arrival.tripId },
        )
        assertEquals(
            first.arrivals.map { it.arrival.estimatedArrivalAt },
            second.arrivals.map { it.arrival.estimatedArrivalAt },
        )
    }
}
