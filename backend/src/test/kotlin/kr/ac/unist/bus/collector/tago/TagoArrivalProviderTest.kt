package kr.ac.unist.bus.collector.tago

import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.config.ArrivalProperties
import kr.ac.unist.bus.route.UnistRouteCatalog
import kr.ac.unist.bus.stop.UnistStopCatalog
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.assertEquals

class TagoArrivalProviderTest {
    private val provider = TagoArrivalProvider(
        restClientBuilder = RestClient.builder(),
        objectMapper = ObjectMapper(),
        properties = ArrivalProperties(mode = ArrivalProperties.Mode.TAGO),
        routeCatalog = UnistRouteCatalog(),
    )

    @Test
    fun `decodes TAGO arrivals and keeps only UNIST routes`() {
        val payload = """
            {
              "response": {
                "header": { "resultCode": "00", "resultMsg": "OK" },
                "body": {
                  "items": {
                    "item": [
                      {
                        "nodeid": "USB123",
                        "nodenm": "울산과학기술원",
                        "routeid": "USB713",
                        "routeno": "713",
                        "arrtime": 420
                      },
                      {
                        "nodeid": "USB123",
                        "nodenm": "울산과학기술원",
                        "routeid": "USB999",
                        "routeno": "999",
                        "arrtime": 600
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()
        val observedAt = Instant.parse("2026-07-24T09:00:00Z")

        val batch = provider.decode(
            payload = payload,
            stop = UnistStopCatalog().require("40234"),
            providerNodeId = "USB123",
            observedAt = observedAt,
        )

        assertEquals(listOf("713"), batch.arrivals.map { it.routeName })
        assertEquals(observedAt.plusSeconds(420), batch.arrivals.single().estimatedArrivalAt)
        assertEquals(ArrivalStatus.APPROACHING, batch.arrivals.single().status)
    }

    @Test
    fun `accepts a single TAGO item object`() {
        val payload = """
            {
              "response": {
                "header": { "resultCode": "00", "resultMsg": "OK" },
                "body": {
                  "items": {
                    "item": {
                      "routeid": "USB513",
                      "routeno": "513",
                      "arrtime": 45
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val batch = provider.decode(
            payload = payload,
            stop = UnistStopCatalog().require("40234"),
            providerNodeId = "USB123",
            observedAt = Instant.parse("2026-07-24T09:00:00Z"),
        )

        assertEquals(1, batch.arrivals.size)
        assertEquals(ArrivalStatus.ARRIVING, batch.arrivals.single().status)
    }
}
