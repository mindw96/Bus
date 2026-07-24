package kr.ac.unist.bus.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalTextTest {
    @Test
    fun remainingTimeUsesExplicitKoreanUnits() {
        val now = Instant.parse("2026-07-24T00:00:00Z")
        val eta = now.plusSeconds(7 * 60 + 28L)

        assertEquals("7분 28초", ArrivalText.remainingTime(eta, now))
    }

    @Test
    fun originStopUsesDepartureWording() {
        val now = Instant.parse("2026-07-24T00:00:00Z")
        val item = stopArrival(
            stop = BusStop("40233", "울산과학기술원(기점)"),
            eta = now.plusSeconds(90),
        )

        assertEquals(CountdownKind.DEPARTURE, item.countdownKind)
        assertEquals("남은 시간: 1분 30초", ArrivalText.status(item, now))
    }

    @Test
    fun staleDataDoesNotPretendCountdownIsLive() {
        val now = Instant.parse("2026-07-24T00:10:00Z")
        val item = stopArrival(
            stop = BusStop("40234", "울산과학기술원"),
            eta = now.plusSeconds(90),
            staleAt = now.minusSeconds(1),
        )

        assertEquals("데이터 지연", ArrivalText.status(item, now))
    }

    private fun stopArrival(
        stop: BusStop,
        eta: Instant,
        staleAt: Instant = eta.plusSeconds(60),
    ): StopArrival = StopArrival(
        stop = stop,
        arrival = Arrival(
            tripId = "trip",
            routeId = "route-713",
            routeName = "713",
            destination = "덕하 차고지",
            scheduledArrivalAt = eta,
            estimatedArrivalAt = eta,
            observedAt = eta.minusSeconds(60),
            staleAt = staleAt,
            status = ArrivalStatus.SCHEDULED,
        ),
    )
}
