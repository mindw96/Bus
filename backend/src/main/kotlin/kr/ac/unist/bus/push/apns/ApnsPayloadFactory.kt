package kr.ac.unist.bus.push.apns

import kr.ac.unist.bus.push.domain.PushMessage
import kr.ac.unist.bus.push.domain.PushMessageType
import org.springframework.stereotype.Component

@Component
class ApnsPayloadFactory {
    fun create(message: PushMessage): Map<String, Any> {
        val arrival = message.arrival
        val aps = linkedMapOf<String, Any>(
            "timestamp" to message.timestamp.epochSecond,
            "event" to if (message.type == PushMessageType.TRACKING_END) "end" else "update",
            "content-state" to mapOf(
                "estimatedArrivalEpochSeconds" to arrival.estimatedArrivalAt.epochSecond,
                "observedAtEpochSeconds" to arrival.observedAt.epochSecond,
                "staleAtEpochSeconds" to arrival.staleAt.epochSecond,
                "status" to arrival.status.name,
            ),
            "stale-date" to arrival.staleAt.epochSecond,
        )
        if (message.type == PushMessageType.TRACKING_END) {
            aps["dismissal-date"] = message.timestamp.plusSeconds(15 * 60).epochSecond
        }
        return mapOf("aps" to aps)
    }
}
