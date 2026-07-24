package kr.ac.unist.bus.data

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpArrivalRepositoryTest {
    @Test
    fun fetchMergesThreeStopsAndKeepsEarliestArrivalPerRoute() = runBlocking {
        val transport = StopArrivalTransport { stopId ->
            when (stopId) {
                "40234" -> response(
                    stopId = stopId,
                    stopName = "울산과학기술원",
                    routeName = "513",
                    eta = "2026-07-24T09:05:00Z",
                )
                "40233" -> response(
                    stopId = stopId,
                    stopName = "울산과학기술원(기점)",
                    routeName = "713",
                    eta = "2026-07-24T09:07:00Z",
                )
                else -> response(
                    stopId = stopId,
                    stopName = "울산과학기술원(종점)",
                    routeName = "713",
                    eta = "2026-07-24T09:20:00Z",
                )
            }
        }
        val repository = HttpArrivalRepository(
            baseUrl = "https://example.com",
            transport = transport,
        )

        val snapshot = repository.fetch()

        assertEquals(listOf("513", "713"), snapshot.arrivals.map { it.arrival.routeName })
        assertEquals("40233", snapshot.arrivals.single { it.arrival.routeName == "713" }.stop.id)
        assertEquals(Instant.parse("2026-07-24T09:00:00Z"), snapshot.generatedAt)
    }

    @Test
    fun decoderMapsUnknownFutureStatusSafely() {
        val decoded = ArrivalResponseDecoder.decode(
            response(
                stopId = "40234",
                stopName = "울산과학기술원",
                routeName = "513",
                eta = "2026-07-24T09:05:00Z",
                status = "FUTURE_STATUS",
            ),
        )

        assertEquals("UNKNOWN", decoded.arrivals.single().arrival.status.name)
    }

    @Test
    fun fetchKeepsSuccessfulStopsWhenOneStopFails() = runBlocking {
        val repository = HttpArrivalRepository(
            baseUrl = "https://example.com",
            transport = StopArrivalTransport { stopId ->
                if (stopId == "40233") {
                    error("temporary failure")
                }
                response(
                    stopId = stopId,
                    stopName = "울산과학기술원",
                    routeName = if (stopId == "40234") "513" else "713",
                    eta = if (stopId == "40234") {
                        "2026-07-24T09:05:00Z"
                    } else {
                        "2026-07-24T09:07:00Z"
                    },
                )
            },
        )

        val snapshot = repository.fetch()

        assertEquals(listOf("513", "713"), snapshot.arrivals.map { it.arrival.routeName })
    }

    private fun response(
        stopId: String,
        stopName: String,
        routeName: String,
        eta: String,
        status: String = "APPROACHING",
    ): String =
        """
        {
          "serverTime": "2026-07-24T09:00:00Z",
          "freshness": "FRESH",
          "stop": {"id": "$stopId", "name": "$stopName"},
          "arrivals": [{
            "tripId": "trip-$stopId-$routeName",
            "routeId": "route-$routeName",
            "routeName": "$routeName",
            "destination": "UNIST",
            "scheduledArrivalAt": "$eta",
            "estimatedArrivalAt": "$eta",
            "observedAt": "2026-07-24T09:00:00Z",
            "staleAt": "2026-07-24T09:02:00Z",
            "status": "$status"
          }]
        }
        """.trimIndent()
}
