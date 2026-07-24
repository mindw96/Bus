package kr.ac.unist.bus.route

import org.springframework.stereotype.Component

@Component
class UnistRouteCatalog {
    val supportedRouteNames: Set<String> = setOf("513", "713", "743", "753", "1115")

    fun destination(stopId: String, routeName: String): String =
        when (stopId) {
            "40235" -> "UNIST"
            else -> when (routeName) {
                "513", "713", "743", "753", "1115" -> "덕하 차고지"
                else -> "운행 방향 확인"
            }
        }
}
