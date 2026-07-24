package kr.ac.unist.bus.arrival.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(
    properties = [
        "unist-bus.arrivals.mode=fixture",
    ],
)
@AutoConfigureMockMvc
class ArrivalApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `returns an iOS compatible response for a UNIST stop`() {
        mockMvc.get("/api/v1/stops/40234/arrivals")
            .andExpect {
                status { isOk() }
                header { string("Cache-Control", "no-store") }
                header { string("X-Data-Freshness", "FRESH") }
                jsonPath("$.stop.id") { value("40234") }
                jsonPath("$.stop.name") { value("울산과학기술원") }
                jsonPath("$.arrivals[0].routeName") { value("513") }
                jsonPath("$.arrivals[0].estimatedArrivalAt") { exists() }
                jsonPath("$.arrivals[0].staleAt") { exists() }
                jsonPath("$.arrivals[0].status") { value("APPROACHING") }
            }
    }

    @Test
    fun `returns RFC 9457 problem details for an unsupported stop`() {
        mockMvc.get("/api/v1/stops/99999/arrivals")
            .andExpect {
                status { isNotFound() }
                content { contentType("application/problem+json") }
                jsonPath("$.title") { value("정류장을 찾을 수 없습니다") }
                jsonPath("$.status") { value(404) }
            }
    }
}
