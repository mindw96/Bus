package kr.ac.unist.bus.push

import java.time.Instant
import kr.ac.unist.bus.model.Arrival
import kr.ac.unist.bus.model.ArrivalStatus
import kr.ac.unist.bus.model.BusStop
import kr.ac.unist.bus.model.StopArrival

enum class ArrivalPushType {
    ARRIVAL_UPDATE,
    TRACKING_END,
}

data class ArrivalPushPayload(
    val type: ArrivalPushType,
    val trackingSessionId: String,
    val observedAt: Instant,
    val item: StopArrival?,
    val reason: String?,
)

object ArrivalPushPayloadDecoder {
    private const val SUPPORTED_SCHEMA_VERSION = "1"

    fun decode(data: Map<String, String>): ArrivalPushPayload? = runCatching {
        require(data.required("schemaVersion") == SUPPORTED_SCHEMA_VERSION)
        val type = ArrivalPushType.valueOf(data.required("type").uppercase())
        val trackingSessionId = data.required("trackingSessionId")
        val observedAt = Instant.parse(data.required("observedAt"))

        when (type) {
            ArrivalPushType.ARRIVAL_UPDATE -> ArrivalPushPayload(
                type = type,
                trackingSessionId = trackingSessionId,
                observedAt = observedAt,
                item = StopArrival(
                    stop = BusStop(
                        id = data.required("stopId"),
                        name = data.required("stopName"),
                    ),
                    arrival = Arrival(
                        tripId = data.required("tripId"),
                        routeId = data.required("routeId"),
                        routeName = data.required("routeName"),
                        destination = data.required("destination"),
                        scheduledArrivalAt = Instant.parse(data.required("estimatedArrivalAt")),
                        estimatedArrivalAt = Instant.parse(data.required("estimatedArrivalAt")),
                        observedAt = observedAt,
                        staleAt = Instant.parse(data.required("staleAt")),
                        status = data.required("status")
                            .uppercase()
                            .let { rawStatus ->
                                ArrivalStatus.entries.firstOrNull { it.name == rawStatus }
                                    ?: ArrivalStatus.UNKNOWN
                            },
                    ),
                ),
                reason = null,
            )

            ArrivalPushType.TRACKING_END -> ArrivalPushPayload(
                type = type,
                trackingSessionId = trackingSessionId,
                observedAt = observedAt,
                item = null,
                reason = data["reason"]?.takeIf(String::isNotBlank),
            )
        }
    }.getOrNull()

    private fun Map<String, String>.required(key: String): String =
        get(key)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Missing push field: $key")
}
