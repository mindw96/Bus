package kr.ac.unist.bus.push.fcm

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import kr.ac.unist.bus.push.domain.PushMessage
import kr.ac.unist.bus.push.domain.PushMessageType
import kr.ac.unist.bus.push.port.PushGateway
import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component
@ConditionalOnProperty(
    prefix = "unist-bus.push.fcm",
    name = ["enabled"],
    havingValue = "true",
)
class FcmPushGateway(
    firebaseApp: FirebaseApp,
) : PushGateway {
    private val messaging = FirebaseMessaging.getInstance(firebaseApp)

    override val platform = TrackingPlatform.ANDROID_FCM

    override fun send(
        pushTarget: String,
        environment: PushEnvironment,
        message: PushMessage,
    ) {
        val androidConfig = AndroidConfig.builder()
            .setPriority(AndroidConfig.Priority.HIGH)
            .setCollapseKey(message.trackingSessionId.toString())
            .setTtl(message.fcmTtlMillis())
            .build()
        val request = Message.builder()
            .setFid(pushTarget)
            .putAllData(message.androidData())
            .setAndroidConfig(androidConfig)
            .build()
        try {
            messaging.sendAsync(request).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FcmPushException()
        } catch (_: Exception) {
            throw FcmPushException()
        }
    }
}

class FcmPushException : RuntimeException("FCM 전송에 실패했습니다.")

internal fun PushMessage.fcmTtlMillis(): Long {
    val remaining = Duration.between(timestamp, expiresAt)
        .toMillis()
        .coerceAtLeast(0)
    val requested = if (type == PushMessageType.TRACKING_END) {
        maxOf(remaining, MIN_END_TTL.toMillis())
    } else {
        remaining
    }
    return requested.coerceAtMost(MAX_FCM_TTL.toMillis())
}

private val MIN_END_TTL: Duration = Duration.ofMinutes(15)
private val MAX_FCM_TTL: Duration = Duration.ofDays(28)
private const val SEND_TIMEOUT_SECONDS = 8L
