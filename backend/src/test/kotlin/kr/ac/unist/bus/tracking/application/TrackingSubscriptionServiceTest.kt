package kr.ac.unist.bus.tracking.application

import kr.ac.unist.bus.arrival.domain.Freshness
import kr.ac.unist.bus.config.PushProperties
import kr.ac.unist.bus.stop.UnistStopCatalog
import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import kr.ac.unist.bus.tracking.domain.TrackingState
import kr.ac.unist.bus.tracking.domain.TrackingSubscription
import kr.ac.unist.bus.tracking.repository.InMemoryTrackingSubscriptionRepository
import kr.ac.unist.bus.tracking.security.AesGcmPushTargetCipher
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrackingSubscriptionServiceTest {
    private val now = Instant.parse("2026-07-25T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val cipher = AesGcmPushTargetCipher(ByteArray(32) { it.toByte() })
    private val repository = InMemoryTrackingSubscriptionRepository()
    private val service = TrackingSubscriptionService(
        repository = repository,
        stopCatalog = UnistStopCatalog(),
        cipher = cipher,
        properties = PushProperties(),
        clock = clock,
    )

    @Test
    fun `same installation migrates a legacy plaintext owner and remains idempotent`() {
        repository.mutate(sessionId) { legacySubscription(installationId) }

        val migrated = service.upsert(sessionId, command(installationId))
        val repeated = service.upsert(sessionId, command(installationId))

        assertNotEquals(installationId, migrated.installationIdentifier)
        assertTrue(migrated.installationIdentifier.startsWith("hmac-sha256:"))
        assertEquals(migrated.installationIdentifier, repeated.installationIdentifier)
        assertEquals(
            cipher.installationIdentifier(installationId),
            repository.findById(sessionId)?.installationIdentifier,
        )
    }

    @Test
    fun `a different installation still cannot take over a migrated session`() {
        service.upsert(sessionId, command(installationId))

        assertFailsWith<TrackingSubscriptionConflictException> {
            service.upsert(sessionId, command("c9999999999_other_FID"))
        }
    }

    private fun command(owner: String) = UpsertTrackingSubscription(
        installationId = owner,
        platform = TrackingPlatform.ANDROID_FCM,
        pushTarget = owner,
        pushEnvironment = PushEnvironment.DEVELOPMENT,
        tripId = "trip-713",
        routeId = "route-713",
        routeName = "713",
        stopId = "40233",
        estimatedArrivalAt = now.plusSeconds(10 * 60),
        expiresAt = now.plusSeconds(30 * 60),
    )

    private fun legacySubscription(owner: String) = TrackingSubscription(
        trackingSessionId = sessionId,
        installationIdentifier = owner,
        platform = TrackingPlatform.ANDROID_FCM,
        pushTarget = cipher.encrypt(owner),
        pushEnvironment = PushEnvironment.DEVELOPMENT,
        tripId = "trip-713",
        routeId = "route-713",
        routeName = "713",
        stopId = "40233",
        initialEstimatedArrivalAt = now.plusSeconds(10 * 60),
        expiresAt = now.plusSeconds(30 * 60),
        state = TrackingState.ACTIVE,
        createdAt = now.minusSeconds(60),
        updatedAt = now.minusSeconds(60),
        lastMatchedArrival = null,
        lastDeliveredEta = now.plusSeconds(10 * 60),
        lastDeliveredStatus = null,
        lastDeliveredFreshness = Freshness.FRESH,
    )

    private companion object {
        const val installationId = "c1234567890_example_FID"
        val sessionId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
    }
}
