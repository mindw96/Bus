package kr.ac.unist.bus.collector.tago

import kr.ac.unist.bus.arrival.domain.Arrival
import kr.ac.unist.bus.arrival.domain.ArrivalBatch
import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.collector.ArrivalProvider
import kr.ac.unist.bus.collector.ArrivalProviderUnavailableException
import kr.ac.unist.bus.config.ArrivalProperties
import kr.ac.unist.bus.route.UnistRouteCatalog
import kr.ac.unist.bus.stop.SupportedStop
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Component
@ConditionalOnProperty(
    prefix = "unist-bus.arrivals",
    name = ["mode"],
    havingValue = "tago",
)
class TagoArrivalProvider(
    restClientBuilder: RestClient.Builder,
    private val objectMapper: ObjectMapper,
    private val properties: ArrivalProperties,
    private val routeCatalog: UnistRouteCatalog,
) : ArrivalProvider {
    private val restClient = restClientBuilder.build()

    override fun fetch(stop: SupportedStop, observedAt: Instant): ArrivalBatch {
        val tago = properties.tago
        val providerNodeId = tago.nodeIds[stop.id].orEmpty()
        if (tago.serviceKey.isBlank() || tago.cityCode.isBlank() || providerNodeId.isBlank()) {
            throw ArrivalProviderUnavailableException(
                "TAGO 인증키, 도시코드 또는 정류소 ID 매핑이 설정되지 않았습니다.",
            )
        }

        val uri = UriComponentsBuilder.fromUri(tago.baseUrl)
            .pathSegment("getSttnAcctoArvlPrearngeInfoList")
            .queryParam("serviceKey", tago.serviceKey)
            .queryParam("pageNo", 1)
            .queryParam("numOfRows", 100)
            .queryParam("_type", "json")
            .queryParam("cityCode", tago.cityCode)
            .queryParam("nodeId", providerNodeId)
            .encode()
            .build()
            .toUri()

        val body = try {
            restClient.get()
                .uri(uri)
                .retrieve()
                .body(String::class.java)
        } catch (_: RestClientException) {
            // RestClient exceptions may contain the request URI. Do not propagate a
            // cause that can leak the public-data service key into application logs.
            throw ArrivalProviderUnavailableException()
        } ?: throw ArrivalProviderUnavailableException("TAGO가 빈 응답을 반환했습니다.")

        return decode(body, stop, providerNodeId, observedAt)
    }

    internal fun decode(
        payload: String,
        stop: SupportedStop,
        providerNodeId: String,
        observedAt: Instant,
    ): ArrivalBatch {
        val root = try {
            objectMapper.readTree(payload)
        } catch (_: Exception) {
            throw ArrivalProviderUnavailableException("TAGO 응답 형식을 해석할 수 없습니다.")
        }

        val response = root.path("response")
        val resultCode = response.path("header").path("resultCode").asString()
        if (resultCode != "00") {
            throw ArrivalProviderUnavailableException("TAGO가 오류 응답을 반환했습니다. (코드 $resultCode)")
        }

        val arrivals = itemNodes(response.path("body").path("items").path("item"))
            .mapNotNull { item -> mapItem(item, stop, providerNodeId, observedAt) }
            .filter { it.routeName in routeCatalog.supportedRouteNames }
            .sortedBy(Arrival::estimatedArrivalAt)

        return ArrivalBatch(
            stop = stop,
            arrivals = arrivals,
            observedAt = observedAt,
            staleAt = observedAt.plus(properties.staleAfter),
        )
    }

    private fun itemNodes(node: JsonNode): List<JsonNode> =
        when {
            node.isArray -> node.toList()
            node.isObject -> listOf(node)
            else -> emptyList()
        }

    private fun mapItem(
        item: JsonNode,
        stop: SupportedStop,
        providerNodeId: String,
        observedAt: Instant,
    ): Arrival? {
        val routeName = item.path("routeno").asString().trim()
        val providerRouteId = item.path("routeid").asString().trim()
        val secondsUntilArrival = item.path("arrtime").asInt(-1)
        if (routeName.isBlank() || providerRouteId.isBlank() || secondsUntilArrival < 0) {
            return null
        }

        val estimatedAt = observedAt.plusSeconds(secondsUntilArrival.toLong())
        val tripBucket = estimatedAt.epochSecond / TRIP_BUCKET_SECONDS
        return Arrival(
            tripId = "tago-$providerNodeId-$providerRouteId-$tripBucket",
            routeId = "tago-$providerRouteId",
            routeName = routeName,
            destination = routeCatalog.destination(stop.id, routeName),
            scheduledArrivalAt = estimatedAt,
            estimatedArrivalAt = estimatedAt,
            observedAt = observedAt,
            staleAt = observedAt.plus(properties.staleAfter),
            status = status(secondsUntilArrival),
        )
    }

    private fun status(secondsUntilArrival: Int): ArrivalStatus =
        when {
            secondsUntilArrival <= 60 -> ArrivalStatus.ARRIVING
            secondsUntilArrival <= 15 * 60 -> ArrivalStatus.APPROACHING
            else -> ArrivalStatus.SCHEDULED
        }

    private companion object {
        const val TRIP_BUCKET_SECONDS = 10 * 60L
    }
}
