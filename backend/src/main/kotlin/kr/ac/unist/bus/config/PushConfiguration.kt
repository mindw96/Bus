package kr.ac.unist.bus.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Firestore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import kr.ac.unist.bus.tracking.security.AesGcmPushTargetCipher
import kr.ac.unist.bus.tracking.security.PushTargetCipher
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.security.SecureRandom
import java.util.Base64

@Configuration
class PushConfiguration {
    @Bean
    fun pushConfigurationValidator(
        tracking: TrackingProperties,
        push: PushProperties,
    ) = InitializingBean {
        require(push.etaChangeThreshold > java.time.Duration.ZERO) {
            "푸시 ETA 임계값은 0보다 커야 합니다."
        }
        require(push.matchWindow > java.time.Duration.ZERO) {
            "푸시 운행 매칭 범위는 0보다 커야 합니다."
        }
        require(push.matchWindow <= java.time.Duration.ofMinutes(5)) {
            "푸시 운행 매칭 범위는 5분 이하여야 합니다."
        }
        require(push.iosHeartbeatInterval > java.time.Duration.ZERO) {
            "iOS heartbeat 간격은 0보다 커야 합니다."
        }
        require(push.deliveryClaimLease > java.time.Duration.ZERO) {
            "푸시 전송 claim 시간은 0보다 커야 합니다."
        }
        require(
            push.maxSubscriptionLifetime > java.time.Duration.ZERO &&
                push.arrivalGracePeriod > java.time.Duration.ZERO,
        ) {
            "추적 수명과 도착 유예 시간은 0보다 커야 합니다."
        }
        if (tracking.storage == TrackingProperties.Storage.FIRESTORE) {
            require(tracking.encryptionKeyBase64.isNotBlank()) {
                "Firestore 저장소에는 UNIST_BUS_TRACKING_ENCRYPTION_KEY가 필요합니다."
            }
            require(push.firebase.projectId.isNotBlank()) {
                "Firestore 저장소에는 FIREBASE_PROJECT_ID가 필요합니다."
            }
        }
        if (push.enabled) {
            require(tracking.storage == TrackingProperties.Storage.FIRESTORE) {
                "운영 푸시를 활성화하려면 Firestore 저장소가 필요합니다."
            }
            require(push.refreshKey.isNotBlank()) {
                "푸시 worker에는 UNIST_BUS_PUSH_REFRESH_KEY가 필요합니다."
            }
            require(push.fcm.enabled || push.apns.enabled) {
                "푸시를 활성화하려면 FCM 또는 APNs gateway가 필요합니다."
            }
        }
        if (push.fcm.enabled) {
            require(push.firebase.projectId.isNotBlank()) {
                "FCM에는 FIREBASE_PROJECT_ID가 필요합니다."
            }
        }
        if (push.apns.enabled) {
            require(
                push.apns.keyId.isNotBlank() &&
                    push.apns.teamId.isNotBlank() &&
                    push.apns.bundleId.isNotBlank() &&
                    push.apns.authKey.isNotBlank(),
            ) {
                "APNs에는 key ID, team ID, bundle ID와 인증키가 필요합니다."
            }
        }
    }

    @Bean
    fun pushTargetCipher(tracking: TrackingProperties): PushTargetCipher {
        val key = if (tracking.encryptionKeyBase64.isBlank()) {
            ByteArray(32).also(SecureRandom()::nextBytes)
        } else {
            try {
                Base64.getDecoder().decode(tracking.encryptionKeyBase64)
            } catch (_: IllegalArgumentException) {
                throw IllegalStateException("추적 암호화 키는 Base64 형식이어야 합니다.")
            }
        }
        require(key.size == 32) {
            "추적 암호화 키는 Base64로 인코딩한 32바이트 키여야 합니다."
        }
        return AesGcmPushTargetCipher(key)
    }

    @Bean(destroyMethod = "delete")
    @ConditionalOnExpression(
        "'\${unist-bus.tracking.storage:memory}' == 'firestore' or " +
            "'\${unist-bus.push.fcm.enabled:false}' == 'true'",
    )
    fun firebaseApp(push: PushProperties): FirebaseApp {
        val credentials = try {
            GoogleCredentials.getApplicationDefault()
        } catch (_: Exception) {
            throw IllegalStateException("Firebase용 Application Default Credentials를 찾을 수 없습니다.")
        }
        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .setProjectId(push.firebase.projectId)
            .build()
        return FirebaseApp.initializeApp(options, "unist-bus-${System.identityHashCode(options)}")
    }

    @Bean
    @ConditionalOnExpression("'\${unist-bus.tracking.storage:memory}' == 'firestore'")
    fun firestore(firebaseApp: FirebaseApp): Firestore = FirestoreClient.getFirestore(firebaseApp)
}
