package kr.ac.unist.bus

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "unist-bus.arrivals.mode=tago",
    ],
)
class TagoModeContextTest {
    @Test
    fun `application context starts in TAGO mode`() {
    }
}
