package kr.ac.unist.bus.ui

import java.time.Instant
import kr.ac.unist.bus.model.Arrival
import kr.ac.unist.bus.model.ArrivalStatus
import kr.ac.unist.bus.model.BusStop
import kr.ac.unist.bus.model.StopArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MainRouteListTest {
    @Test
    fun emptyArrivalsShowAllFiveRoutesAsNonTrackablePlaceholders() {
        val items = mainRouteListItems(emptyList())

        assertEquals(MAIN_ROUTE_NAMES, items.map(MainRouteListItem::routeName))
        assertTrue(items.all { it.arrival == null })
        assertTrue(items.none(MainRouteListItem::canTrack))
    }

    @Test
    fun actualArrivalsKeepTheirOrderAndFillOnlyMissingRoutes() {
        val route713 = stopArrival("713", etaSeconds = 300)
        val route513 = stopArrival("513", etaSeconds = 600)

        val items = mainRouteListItems(listOf(route713, route513))

        assertEquals(
            listOf("713", "513", "743", "753", "1115"),
            items.map(MainRouteListItem::routeName),
        )
        assertSame(route713, items[0].arrival)
        assertSame(route513, items[1].arrival)
        assertTrue(items.take(2).all(MainRouteListItem::canTrack))
        assertTrue(items.drop(2).none(MainRouteListItem::canTrack))
    }

    @Test
    fun unrelatedRoutesAndDuplicateRowsDoNotCreateExtraCards() {
        val first713 = stopArrival("713", etaSeconds = 300)
        val duplicate713 = stopArrival("713", etaSeconds = 600)
        val unrelated = stopArrival("999", etaSeconds = 120)

        val items = mainRouteListItems(listOf(unrelated, first713, duplicate713))

        assertEquals(
            listOf("713", "513", "743", "753", "1115"),
            items.map(MainRouteListItem::routeName),
        )
        assertEquals(5, items.size)
        assertSame(first713, items.first().arrival)
        assertFalse(items.first().arrival === duplicate713)
    }

    private fun stopArrival(
        routeName: String,
        etaSeconds: Long,
    ): StopArrival {
        val now = Instant.parse("2026-07-25T03:00:00Z")
        val eta = now.plusSeconds(etaSeconds)
        return StopArrival(
            stop = BusStop(
                id = "40234",
                name = "울산과학기술원",
            ),
            arrival = Arrival(
                tripId = "trip-$routeName-$etaSeconds",
                routeId = "route-$routeName",
                routeName = routeName,
                destination = "UNIST",
                scheduledArrivalAt = eta,
                estimatedArrivalAt = eta,
                observedAt = now,
                staleAt = now.plusSeconds(120),
                status = ArrivalStatus.APPROACHING,
            ),
        )
    }
}
