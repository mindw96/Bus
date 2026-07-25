package kr.ac.unist.bus.push

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kr.ac.unist.bus.notification.PersistedTrackingState
import org.json.JSONObject

enum class PushEnvironment {
    DEVELOPMENT,
    PRODUCTION,
}

data class TrackingSubscriptionRequest(
    val trackingSessionId: String,
    val installationId: String,
    val pushEnvironment: PushEnvironment,
    val tripId: String,
    val routeId: String,
    val routeName: String,
    val stopId: String,
    val estimatedArrivalAt: Instant,
    val expiresAt: Instant,
) {
    companion object {
        fun from(
            state: PersistedTrackingState,
            pushEnvironment: PushEnvironment,
        ): TrackingSubscriptionRequest? {
            val installationId = state.installationId?.takeIf(String::isNotBlank) ?: return null
            return TrackingSubscriptionRequest(
                trackingSessionId = state.trackingSessionId,
                installationId = installationId,
                pushEnvironment = pushEnvironment,
                tripId = state.tripId,
                routeId = state.routeId,
                routeName = state.routeName,
                stopId = state.stopId,
                estimatedArrivalAt = state.estimatedArrivalAt,
                expiresAt = state.expiresAt,
            )
        }
    }
}

data class TrackingSubscriptionResponse(
    val trackingSessionId: String,
    val state: String,
    val updatedAt: Instant,
    val expiresAt: Instant,
)

interface TrackingSubscriptionClient {
    fun put(request: TrackingSubscriptionRequest): TrackingSubscriptionResponse

    fun delete(trackingSessionId: String)
}

class HttpTrackingSubscriptionClient(
    baseUrl: String,
) : TrackingSubscriptionClient {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    init {
        require(normalizedBaseUrl.startsWith("https://")) {
            "운영 API 주소는 HTTPS여야 합니다."
        }
    }

    override fun put(request: TrackingSubscriptionRequest): TrackingSubscriptionResponse {
        val connection = openConnection(request.trackingSessionId)
        return try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { output ->
                output.write(TrackingSubscriptionJson.encode(request).toByteArray(Charsets.UTF_8))
            }

            ensureSuccessful(connection)
            TrackingSubscriptionJson.decodeResponse(
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() },
            )
        } finally {
            connection.disconnect()
        }
    }

    override fun delete(trackingSessionId: String) {
        val connection = openConnection(trackingSessionId)
        try {
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("Accept", "application/json")
            val statusCode = connection.responseCode
            if (statusCode !in 200..299 && statusCode != HttpURLConnection.HTTP_NOT_FOUND) {
                throw IOException("추적 구독 삭제 서버가 HTTP $statusCode 응답을 반환했습니다.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(trackingSessionId: String): HttpURLConnection {
        require(SESSION_ID_PATTERN.matches(trackingSessionId)) {
            "올바르지 않은 추적 세션 ID입니다."
        }
        return URL("$normalizedBaseUrl/api/v1/tracking-subscriptions/$trackingSessionId")
            .openConnection()
            .let { it as HttpURLConnection }
            .apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
            }
    }

    private fun ensureSuccessful(connection: HttpURLConnection) {
        val statusCode = connection.responseCode
        if (statusCode !in 200..299) {
            throw IOException("추적 구독 서버가 HTTP $statusCode 응답을 반환했습니다.")
        }
    }

    private companion object {
        val SESSION_ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 10_000
    }
}

internal object TrackingSubscriptionJson {
    fun encode(request: TrackingSubscriptionRequest): String = JSONObject()
        .put("installationId", request.installationId)
        .put("platform", "ANDROID_FCM")
        .put("pushTarget", request.installationId)
        .put("pushEnvironment", request.pushEnvironment.name)
        .put("tripId", request.tripId)
        .put("routeId", request.routeId)
        .put("routeName", request.routeName)
        .put("stopId", request.stopId)
        .put("estimatedArrivalAt", request.estimatedArrivalAt.toString())
        .put("expiresAt", request.expiresAt.toString())
        .toString()

    fun decodeResponse(payload: String): TrackingSubscriptionResponse {
        val json = JSONObject(payload)
        return TrackingSubscriptionResponse(
            trackingSessionId = json.getString("trackingSessionId"),
            state = json.getString("state"),
            updatedAt = Instant.parse(json.getString("updatedAt")),
            expiresAt = Instant.parse(json.getString("expiresAt")),
        )
    }
}
