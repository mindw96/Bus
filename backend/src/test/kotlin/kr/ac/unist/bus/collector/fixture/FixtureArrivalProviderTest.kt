package kr.ac.unist.bus.collector.fixture

import kr.ac.unist.bus.config.ArrivalProperties
import kr.ac.unist.bus.stop.UnistStopCatalog
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixtureArrivalProviderTest {
    private val properties = ArrivalProperties()
    private val provider = FixtureArrivalProvider(properties)
    private val stops = UnistStopCatalog()
    private val observedAt = Instant.parse("2026-07-24T09:00:00Z")

    @Test
    fun `three UNIST stops expose the five expected logical routes`() {
        val arrivals = stops.all()
            .flatMap { provider.fetch(it, observedAt).arrivals }

        assertEquals(
            setOf("513", "713", "743", "753", "1115"),
            arrivals.map { it.routeName }.toSet(),
        )
    }

    @Test
    fun `same schedule slot keeps ETA and trip identity stable`() {
        val stop = stops.require("40233")
        val first = provider.fetch(stop, observedAt).arrivals.first()
        val refreshed = provider.fetch(stop, observedAt.plusSeconds(30)).arrivals.first()

        assertEquals(first.estimatedArrivalAt, refreshed.estimatedArrivalAt)
        assertEquals(first.tripId, refreshed.tripId)
        assertTrue(refreshed.observedAt > first.observedAt)
    }

    @Test
    fun `next hour creates a new trip identity`() {
        val stop = stops.require("40234")
        val first = provider.fetch(stop, observedAt).arrivals.single()
        val nextHour = provider.fetch(stop, observedAt.plusSeconds(60 * 60)).arrivals.single()

        assertTrue(nextHour.estimatedArrivalAt > first.estimatedArrivalAt)
        assertTrue(nextHour.tripId != first.tripId)
    }
}
