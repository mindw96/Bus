package kr.ac.unist.bus.tracking.api

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kr.ac.unist.bus.tracking.application.TrackingSubscriptionService
import kr.ac.unist.bus.tracking.application.UpsertTrackingSubscription
import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import kr.ac.unist.bus.tracking.domain.TrackingState
import kr.ac.unist.bus.tracking.domain.TrackingSubscription
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/tracking-subscriptions")
class TrackingSubscriptionController(
    private val service: TrackingSubscriptionService,
) {
    @PutMapping("/{trackingSessionId}")
    fun upsert(
        @PathVariable trackingSessionId: UUID,
        @Valid @RequestBody request: UpsertTrackingSubscriptionRequest,
    ): TrackingSubscriptionResponse =
        TrackingSubscriptionResponse.from(
            service.upsert(trackingSessionId, request.toCommand()),
        )

    @DeleteMapping("/{trackingSessionId}")
    fun delete(
        @PathVariable trackingSessionId: UUID,
    ): ResponseEntity<Void> {
        service.delete(trackingSessionId)
        return ResponseEntity.noContent().build()
    }
}

data class UpsertTrackingSubscriptionRequest(
    @field:NotBlank @field:Size(max = 256)
    val installationId: String,
    val platform: TrackingPlatform,
    @field:NotBlank @field:Size(max = 4096)
    val pushTarget: String,
    val pushEnvironment: PushEnvironment,
    @field:NotBlank @field:Size(max = 256)
    val tripId: String,
    @field:NotBlank @field:Size(max = 256)
    val routeId: String,
    @field:NotBlank @field:Size(max = 32)
    val routeName: String,
    @field:NotBlank @field:Size(max = 16)
    val stopId: String,
    val estimatedArrivalAt: Instant,
    val expiresAt: Instant,
) {
    fun toCommand() = UpsertTrackingSubscription(
        installationId = installationId,
        platform = platform,
        pushTarget = pushTarget,
        pushEnvironment = pushEnvironment,
        tripId = tripId,
        routeId = routeId,
        routeName = routeName,
        stopId = stopId,
        estimatedArrivalAt = estimatedArrivalAt,
        expiresAt = expiresAt,
    )

    override fun toString(): String =
        "UpsertTrackingSubscriptionRequest(installationId=<redacted>, platform=$platform, " +
            "pushTarget=<redacted>, pushEnvironment=$pushEnvironment, tripId=$tripId, " +
            "routeId=$routeId, routeName=$routeName, stopId=$stopId, " +
            "estimatedArrivalAt=$estimatedArrivalAt, expiresAt=$expiresAt)"
}

data class TrackingSubscriptionResponse(
    val trackingSessionId: UUID,
    val state: TrackingState,
    val updatedAt: Instant,
    val expiresAt: Instant,
) {
    companion object {
        fun from(subscription: TrackingSubscription) =
            TrackingSubscriptionResponse(
                trackingSessionId = subscription.trackingSessionId,
                state = subscription.state,
                updatedAt = subscription.updatedAt,
                expiresAt = subscription.expiresAt,
            )
    }
}
