package kr.ac.unist.bus.push.apns

import kr.ac.unist.bus.config.PushProperties
import kr.ac.unist.bus.push.domain.PushMessage
import kr.ac.unist.bus.push.port.PushGateway
import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class ApnsHttpResponse(
    val statusCode: Int,
    val body: String,
)

fun interface ApnsHttpTransport {
    fun send(request: HttpRequest): ApnsHttpResponse
}

@Component
@ConditionalOnProperty(
    prefix = "unist-bus.push.apns",
    name = ["enabled"],
    havingValue = "true",
)
class JdkApnsHttpTransport : ApnsHttpTransport {
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    override fun send(request: HttpRequest): ApnsHttpResponse {
        val response = client.send(
            request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        return ApnsHttpResponse(
            statusCode = response.statusCode(),
            body = response.body(),
        )
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}

@Component
@ConditionalOnProperty(
    prefix = "unist-bus.push.apns",
    name = ["enabled"],
    havingValue = "true",
)
class ApnsPushGateway(
    private val properties: PushProperties,
    private val tokenProvider: ApnsAuthorizationTokenProvider,
    private val payloadFactory: ApnsPayloadFactory,
    private val objectMapper: ObjectMapper,
    private val transport: ApnsHttpTransport,
) : PushGateway {
    override val platform = TrackingPlatform.IOS_LIVE_ACTIVITY

    override fun send(
        pushTarget: String,
        environment: PushEnvironment,
        message: PushMessage,
    ) {
        if (environment != properties.apns.allowedEnvironment) {
            logger.warn(
                "event=apns_environment_mismatch allowedEnvironment={} requestedEnvironment={}",
                properties.apns.allowedEnvironment,
                environment,
            )
            throw ApnsEnvironmentMismatchException(
                allowedEnvironment = properties.apns.allowedEnvironment,
                requestedEnvironment = environment,
            )
        }

        val host = apnsHost(environment)
        val body = objectMapper.writeValueAsString(payloadFactory.create(message))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$host/3/device/$pushTarget"))
            .version(HttpClient.Version.HTTP_2)
            .timeout(REQUEST_TIMEOUT)
            .header("authorization", "bearer ${tokenProvider.token()}")
            .header("apns-push-type", "liveactivity")
            .header("apns-topic", "${properties.apns.bundleId}.push-type.liveactivity")
            .header("apns-priority", "5")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            transport.send(request)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApnsPushException()
        } catch (failure: Exception) {
            logger.warn(
                "event=apns_transport_failed failureType={}",
                failure.javaClass.simpleName,
            )
            throw ApnsPushException()
        }
        if (response.statusCode !in 200..299) {
            val reason = apnsReason(response.body)
            logger.warn(
                "event=apns_push_rejected status={} reason={}",
                response.statusCode,
                reason,
            )
            throw ApnsPushException(
                statusCode = response.statusCode,
                reason = reason,
            )
        }
    }

    private fun apnsReason(responseBody: String): String =
        try {
            objectMapper.readTree(responseBody)
                ?.get("reason")
                ?.stringValue()
                ?.takeIf(SAFE_REASON::matches)
                ?: UNKNOWN_REASON
        } catch (_: Exception) {
            UNKNOWN_REASON
        }

    private companion object {
        val logger = LoggerFactory.getLogger(ApnsPushGateway::class.java)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(8)
        val SAFE_REASON = Regex("[A-Za-z0-9_.-]{1,128}")
        const val UNKNOWN_REASON = "UNKNOWN"
    }
}

internal fun apnsHost(environment: PushEnvironment): String =
    when (environment) {
        PushEnvironment.DEVELOPMENT -> "https://api.sandbox.push.apple.com"
        PushEnvironment.PRODUCTION -> "https://api.push.apple.com"
    }

class ApnsEnvironmentMismatchException(
    val allowedEnvironment: PushEnvironment,
    val requestedEnvironment: PushEnvironment,
) : RuntimeException("허용되지 않은 APNs 환경으로의 전송을 차단했습니다.")

class ApnsPushException(
    val statusCode: Int? = null,
    val reason: String? = null,
) : RuntimeException(
    if (statusCode == null || reason == null) {
        "APNs 전송에 실패했습니다."
    } else {
        "APNs 전송에 실패했습니다. status=$statusCode reason=$reason"
    },
)
