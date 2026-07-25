package kr.ac.unist.bus.push.apns

import kr.ac.unist.bus.config.PushProperties
import kr.ac.unist.bus.push.domain.PushMessage
import kr.ac.unist.bus.push.port.PushGateway
import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
@ConditionalOnProperty(
    prefix = "unist-bus.push.apns",
    name = ["enabled"],
    havingValue = "true",
)
class ApnsPushGateway(
    private val properties: PushProperties,
    private val jwtProvider: ApnsJwtProvider,
    private val payloadFactory: ApnsPayloadFactory,
    private val objectMapper: ObjectMapper,
) : PushGateway {
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    override val platform = TrackingPlatform.IOS_LIVE_ACTIVITY

    override fun send(
        pushTarget: String,
        environment: PushEnvironment,
        message: PushMessage,
    ) {
        val host = if (environment == PushEnvironment.DEVELOPMENT) {
            "https://api.sandbox.push.apple.com"
        } else {
            "https://api.push.apple.com"
        }
        val body = objectMapper.writeValueAsString(payloadFactory.create(message))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$host/3/device/$pushTarget"))
            .version(HttpClient.Version.HTTP_2)
            .timeout(REQUEST_TIMEOUT)
            .header("authorization", "bearer ${jwtProvider.token()}")
            .header("apns-push-type", "liveactivity")
            .header("apns-topic", "${properties.apns.bundleId}.push-type.liveactivity")
            .header("apns-priority", "5")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApnsPushException()
        } catch (_: Exception) {
            throw ApnsPushException()
        }
        if (response.statusCode() !in 200..299) {
            throw ApnsPushException()
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(8)
    }
}

class ApnsPushException : RuntimeException("APNs 전송에 실패했습니다.")
