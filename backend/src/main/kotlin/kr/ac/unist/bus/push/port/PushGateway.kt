package kr.ac.unist.bus.push.port

import kr.ac.unist.bus.push.domain.PushMessage
import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import org.springframework.stereotype.Component

interface PushGateway {
    val platform: TrackingPlatform

    fun send(
        pushTarget: String,
        environment: PushEnvironment,
        message: PushMessage,
    )
}

@Component
class PushGatewayRegistry(
    gateways: List<PushGateway>,
) {
    private val byPlatform = gateways.associateBy(PushGateway::platform)

    fun require(platform: TrackingPlatform): PushGateway =
        byPlatform[platform] ?: throw PushGatewayUnavailableException(platform)
}

class PushGatewayUnavailableException(
    platform: TrackingPlatform,
) : RuntimeException("$platform push gateway가 비활성화되어 있습니다.")
