package kr.ac.unist.bus.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("unist-bus.arrivals")
data class ArrivalProperties(
    val mode: Mode = Mode.FIXTURE,
    val cacheTtl: Duration = Duration.ofSeconds(30),
    val staleAfter: Duration = Duration.ofMinutes(2),
    val maxStaleAge: Duration = Duration.ofMinutes(10),
    val tago: Tago = Tago(),
) {
    enum class Mode {
        FIXTURE,
        TAGO,
    }

    data class Tago(
        val baseUrl: URI = URI.create("https://apis.data.go.kr/1613000/ArvlInfoInqireService"),
        val serviceKey: String = "",
        val cityCode: String = "",
        val nodeIds: Map<String, String> = emptyMap(),
    )
}
