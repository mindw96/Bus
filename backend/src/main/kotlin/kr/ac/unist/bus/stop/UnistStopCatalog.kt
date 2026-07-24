package kr.ac.unist.bus.stop

import org.springframework.stereotype.Component

data class SupportedStop(
    val id: String,
    val name: String,
)

@Component
class UnistStopCatalog {
    private val stops = listOf(
        SupportedStop(id = "40234", name = "울산과학기술원"),
        SupportedStop(id = "40233", name = "울산과학기술원(기점)"),
        SupportedStop(id = "40235", name = "울산과학기술원(종점)"),
    ).associateBy(SupportedStop::id)

    fun find(stopId: String): SupportedStop? = stops[stopId]

    fun require(stopId: String): SupportedStop =
        find(stopId) ?: throw UnsupportedStopException(stopId)

    fun all(): List<SupportedStop> = stops.values.toList()
}

class UnsupportedStopException(
    val stopId: String,
) : RuntimeException("지원하지 않는 정류장입니다.")
