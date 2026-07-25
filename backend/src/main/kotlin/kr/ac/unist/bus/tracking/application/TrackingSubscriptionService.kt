package kr.ac.unist.bus.tracking.application

import kr.ac.unist.bus.config.PushProperties
import kr.ac.unist.bus.stop.UnistStopCatalog
import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import kr.ac.unist.bus.tracking.domain.TrackingState
import kr.ac.unist.bus.tracking.domain.TrackingSubscription
import kr.ac.unist.bus.tracking.repository.TrackingSubscriptionRepository
import kr.ac.unist.bus.tracking.security.PushTargetCipher
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class TrackingSubscriptionService(
    private val repository: TrackingSubscriptionRepository,
    private val stopCatalog: UnistStopCatalog,
    private val cipher: PushTargetCipher,
    private val properties: PushProperties,
    private val clock: Clock,
) {
    fun upsert(
        trackingSessionId: UUID,
        command: UpsertTrackingSubscription,
    ): TrackingSubscription {
        stopCatalog.require(command.stopId)
        validatePushTarget(command.platform, command.pushTarget)
        val now = clock.instant()
        if (command.estimatedArrivalAt <= now) {
            throw TrackingSubscriptionValidationException("도착 예정 시각은 현재보다 이후여야 합니다.")
        }
        if (command.expiresAt <= now) {
            throw TrackingSubscriptionValidationException("구독 만료 시각은 현재보다 이후여야 합니다.")
        }

        val installationIdentifier = cipher.installationIdentifier(command.installationId)
        val encryptedTarget = cipher.encrypt(command.pushTarget)
        return requireNotNull(
            repository.mutate(trackingSessionId) { existing ->
                if (
                    existing != null &&
                    !cipher.matchesInstallationIdentifier(
                        existing.installationIdentifier,
                        command.installationId,
                    )
                ) {
                    throw TrackingSubscriptionConflictException()
                }
                if (existing != null && existing.state != TrackingState.ACTIVE) {
                    throw TrackingSubscriptionConflictException()
                }
                val initialEta = existing?.initialEstimatedArrivalAt ?: command.estimatedArrivalAt
                val expiresAt = minOf(
                    command.expiresAt,
                    initialEta.plus(properties.arrivalGracePeriod),
                    now.plus(properties.maxSubscriptionLifetime),
                )
                if (expiresAt <= now) {
                    throw TrackingSubscriptionValidationException("서버 만료 정책상 이미 종료된 추적입니다.")
                }
                TrackingSubscription(
                    trackingSessionId = trackingSessionId,
                    installationIdentifier = installationIdentifier,
                    platform = command.platform,
                    pushTarget = encryptedTarget,
                    pushEnvironment = command.pushEnvironment,
                    tripId = command.tripId,
                    routeId = command.routeId,
                    routeName = command.routeName,
                    stopId = command.stopId,
                    initialEstimatedArrivalAt = initialEta,
                    expiresAt = expiresAt,
                    state = TrackingState.ACTIVE,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    lastMatchedArrival = existing?.lastMatchedArrival,
                    lastDeliveredEta = existing?.lastDeliveredEta ?: command.estimatedArrivalAt,
                    lastDeliveredStatus = existing?.lastDeliveredStatus,
                    lastDeliveredFreshness = existing?.lastDeliveredFreshness,
                    lastDeliveredAt = existing?.lastDeliveredAt,
                    deliveryClaimId = null,
                    deliveryClaimUntil = null,
                    revision = existing?.revision ?: 0,
                )
            },
        ) {
            "추적 구독 저장에 실패했습니다."
        }
    }

    fun delete(trackingSessionId: UUID) {
        repository.mutate(trackingSessionId) { existing ->
            existing?.let {
                if (it.state == TrackingState.ACTIVE) {
                    it.copy(
                        state = TrackingState.ENDED,
                        updatedAt = clock.instant(),
                        deliveryClaimId = null,
                        deliveryClaimUntil = null,
                    )
                } else {
                    it
                }
            }
        }
    }

    private fun validatePushTarget(
        platform: TrackingPlatform,
        pushTarget: String,
    ) {
        val isValid = when (platform) {
            TrackingPlatform.ANDROID_FCM ->
                pushTarget.length in 10..256 &&
                    pushTarget.all { it.isLetterOrDigit() || it == '_' || it == '-' }

            TrackingPlatform.IOS_LIVE_ACTIVITY ->
                pushTarget.length in 32..512 &&
                    pushTarget.length % 2 == 0 &&
                    pushTarget.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        }
        if (!isValid) {
            throw TrackingSubscriptionValidationException(
                "플랫폼에 맞지 않는 푸시 대상 형식입니다.",
            )
        }
    }
}

data class UpsertTrackingSubscription(
    val installationId: String,
    val platform: TrackingPlatform,
    val pushTarget: String,
    val pushEnvironment: PushEnvironment,
    val tripId: String,
    val routeId: String,
    val routeName: String,
    val stopId: String,
    val estimatedArrivalAt: Instant,
    val expiresAt: Instant,
) {
    override fun toString(): String =
        "UpsertTrackingSubscription(installationId=<redacted>, platform=$platform, " +
            "pushTarget=<redacted>, pushEnvironment=$pushEnvironment, tripId=$tripId, " +
            "routeId=$routeId, routeName=$routeName, stopId=$stopId, " +
            "estimatedArrivalAt=$estimatedArrivalAt, expiresAt=$expiresAt)"
}

class TrackingSubscriptionConflictException :
    RuntimeException("다른 설치 또는 종료된 추적 세션은 덮어쓸 수 없습니다.")

class TrackingSubscriptionValidationException(
    message: String,
) : RuntimeException(message)
