package kr.ac.unist.bus.notification

import java.time.Instant
import kr.ac.unist.bus.model.Arrival
import kr.ac.unist.bus.model.ArrivalStatus
import kr.ac.unist.bus.model.BusStop
import kr.ac.unist.bus.model.StopArrival
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingNotificationPhaseTest {
    private val now = Instant.parse("2026-07-25T00:00:00Z")

    @Test
    fun activeArrivalUsesCountdownPhase() {
        assertEquals(
            TrackingNotificationPhase.COUNTDOWN,
            trackingNotificationPhase(item(), now),
        )
    }

    @Test
    fun staleArrivalNeverPretendsCountdownIsLive() {
        assertEquals(
            TrackingNotificationPhase.STALE,
            trackingNotificationPhase(item(staleAt = now.minusSeconds(1)), now),
        )
    }

    @Test
    fun arrivingAndUnknownStatesUseExplicitStatus() {
        assertEquals(
            TrackingNotificationPhase.IMMINENT,
            trackingNotificationPhase(item(status = ArrivalStatus.ARRIVING), now),
        )
        assertEquals(
            TrackingNotificationPhase.UNKNOWN,
            trackingNotificationPhase(item(status = ArrivalStatus.UNKNOWN), now),
        )
    }

    private fun item(
        status: ArrivalStatus = ArrivalStatus.SCHEDULED,
        staleAt: Instant = now.plusSeconds(10 * 60),
    ): StopArrival {
        val eta = now.plusSeconds(7 * 60 + 28)
        return StopArrival(
            stop = BusStop("40235", "울산과학기술원(종점)"),
            arrival = Arrival(
                tripId = "trip-1115",
                routeId = "route-1115",
                routeName = "1115",
                destination = "UNIST",
                scheduledArrivalAt = eta,
                estimatedArrivalAt = eta,
                observedAt = now.minusSeconds(10),
                staleAt = staleAt,
                status = status,
            ),
        )
    }
}
