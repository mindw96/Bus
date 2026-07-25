package kr.ac.unist.bus.tracking.repository

import com.google.api.core.ApiFutures
import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.arrival.domain.Freshness
import kr.ac.unist.bus.tracking.application.TrackingSubscriptionConflictException
import kr.ac.unist.bus.tracking.application.TrackingSubscriptionValidationException
import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import kr.ac.unist.bus.tracking.domain.TrackingState
import kr.ac.unist.bus.tracking.domain.TrackingSubscription
import kr.ac.unist.bus.tracking.security.EncryptedPushTarget
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class TrackingSubscriptionRepositoryTest {
    private val now = Instant.parse("2026-07-24T09:00:00Z")

    @Test
    fun `stale worker revision cannot overwrite a rotated push target`() {
        val repository = InMemoryTrackingSubscriptionRepository()
        val initial = assertNotNull(
            repository.mutate(sessionId) { subscription("old-ciphertext") },
        )
        val rotated = assertNotNull(
            repository.mutate(sessionId) { current ->
                current?.copy(
                    pushTarget = EncryptedPushTarget("new-ciphertext", "new-hash"),
                )
            },
        )

        val staleWrite = repository.compareAndMutate(sessionId, initial.revision) { current ->
            current.copy(
                pushTarget = EncryptedPushTarget("worker-ciphertext", "worker-hash"),
            )
        }

        assertNull(staleWrite)
        assertEquals("new-ciphertext", repository.findById(sessionId)?.pushTarget?.ciphertext)
        assertEquals(initial.revision + 1, rotated.revision)
    }

    @Test
    fun `only one concurrent worker can claim the same revision`() {
        val repository = InMemoryTrackingSubscriptionRepository()
        val initial = assertNotNull(
            repository.mutate(sessionId) { subscription("ciphertext") },
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val attempts = List(2) {
                executor.submit<TrackingSubscription?> {
                    start.await()
                    repository.compareAndMutate(sessionId, initial.revision) { current ->
                        current.copy(deliveryClaimId = UUID.randomUUID())
                    }
                }
            }
            start.countDown()
            val results = attempts.map { it.get(2, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it != null })
            assertNotNull(repository.findById(sessionId)?.deliveryClaimId)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `Firestore transaction future preserves only public domain failures`() {
        val conflict = TrackingSubscriptionConflictException()
        val validation = TrackingSubscriptionValidationException("invalid")

        assertSame(
            conflict,
            assertFailsWith<TrackingSubscriptionConflictException> {
                ApiFutures.immediateFailedFuture<String>(conflict).awaitTransactionResult()
            },
        )
        assertSame(
            validation,
            assertFailsWith<TrackingSubscriptionValidationException> {
                ApiFutures.immediateFailedFuture<String>(validation).awaitTransactionResult()
            },
        )
    }

    @Test
    fun `Firestore transaction future sanitizes infrastructure failures`() {
        val secret = "raw-push-token"

        val failure = assertFailsWith<TrackingRepositoryException> {
            ApiFutures.immediateFailedFuture<String>(
                IllegalStateException(secret),
            ).awaitTransactionResult()
        }

        assertFalse(failure.message.orEmpty().contains(secret))
        assertNull(failure.cause)
    }

    private fun subscription(ciphertext: String) = TrackingSubscription(
        trackingSessionId = sessionId,
        installationIdentifier = "hmac-sha256:example",
        platform = TrackingPlatform.ANDROID_FCM,
        pushTarget = EncryptedPushTarget(ciphertext, "hash-$ciphertext"),
        pushEnvironment = PushEnvironment.DEVELOPMENT,
        tripId = "trip-713",
        routeId = "route-713",
        routeName = "713",
        stopId = "40233",
        initialEstimatedArrivalAt = now.plusSeconds(10 * 60),
        expiresAt = now.plusSeconds(2 * 60 * 60),
        state = TrackingState.ACTIVE,
        createdAt = now,
        updatedAt = now,
        lastMatchedArrival = null,
        lastDeliveredEta = now.plusSeconds(10 * 60),
        lastDeliveredStatus = ArrivalStatus.APPROACHING,
        lastDeliveredFreshness = Freshness.FRESH,
    )

    private companion object {
        val sessionId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")
    }
}
