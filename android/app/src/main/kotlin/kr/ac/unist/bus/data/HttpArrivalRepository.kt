package kr.ac.unist.bus.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kr.ac.unist.bus.model.Arrival
import kr.ac.unist.bus.model.ArrivalMerger
import kr.ac.unist.bus.model.ArrivalSnapshot
import kr.ac.unist.bus.model.ArrivalStatus
import kr.ac.unist.bus.model.BusStop
import kr.ac.unist.bus.model.StopArrival
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

class HttpArrivalRepository(
    baseUrl: String,
    private val transport: StopArrivalTransport = UrlConnectionStopArrivalTransport(baseUrl),
) : ArrivalRepository {
    override suspend fun fetch(): ArrivalSnapshot = coroutineScope {
        val results = STOP_IDS.map { stopId ->
            async(Dispatchers.IO) {
                try {
                    ArrivalResponseDecoder.decode(transport.fetch(stopId))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()

        if (results.isEmpty()) {
            throw IOException("실시간 도착 정보를 불러오지 못했습니다.")
        }

        ArrivalSnapshot(
            arrivals = ArrivalMerger.earliestByRoute(results.flatMap(DecodedStopArrivals::arrivals)),
            generatedAt = results.maxOf(DecodedStopArrivals::serverTime),
        )
    }

    private companion object {
        val STOP_IDS = listOf("40234", "40233", "40235")
    }
}

fun interface StopArrivalTransport {
    fun fetch(stopId: String): String
}

class UrlConnectionStopArrivalTransport(
    baseUrl: String,
) : StopArrivalTransport {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    init {
        require(normalizedBaseUrl.startsWith("https://")) {
            "운영 API 주소는 HTTPS여야 합니다."
        }
    }

    override fun fetch(stopId: String): String {
        val connection = URL("$normalizedBaseUrl/api/v1/stops/$stopId/arrivals")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")

            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IOException("도착 정보 서버가 HTTP $statusCode 응답을 반환했습니다.")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 15_000
    }
}

internal data class DecodedStopArrivals(
    val serverTime: Instant,
    val arrivals: List<StopArrival>,
)

internal object ArrivalResponseDecoder {
    fun decode(payload: String): DecodedStopArrivals {
        val root = JSONObject(payload)
        val stopJson = root.getJSONObject("stop")
        val stop = BusStop(
            id = stopJson.getString("id"),
            name = stopJson.getString("name"),
        )
        val arrivalsJson = root.getJSONArray("arrivals")
        val arrivals = buildList {
            repeat(arrivalsJson.length()) { index ->
                val item = arrivalsJson.getJSONObject(index)
                add(
                    StopArrival(
                        stop = stop,
                        arrival = Arrival(
                            tripId = item.getString("tripId"),
                            routeId = item.getString("routeId"),
                            routeName = item.getString("routeName"),
                            destination = item.getString("destination"),
                            scheduledArrivalAt = Instant.parse(item.getString("scheduledArrivalAt")),
                            estimatedArrivalAt = Instant.parse(item.getString("estimatedArrivalAt")),
                            observedAt = Instant.parse(item.getString("observedAt")),
                            staleAt = Instant.parse(item.getString("staleAt")),
                            status = item.optString("status")
                                .let { rawStatus ->
                                    ArrivalStatus.entries.firstOrNull { it.name == rawStatus }
                                        ?: ArrivalStatus.UNKNOWN
                                },
                        ),
                    ),
                )
            }
        }

        return DecodedStopArrivals(
            serverTime = Instant.parse(root.getString("serverTime")),
            arrivals = arrivals,
        )
    }
}
