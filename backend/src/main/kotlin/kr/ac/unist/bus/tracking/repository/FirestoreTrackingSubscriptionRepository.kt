package kr.ac.unist.bus.tracking.repository

import com.google.api.core.ApiFuture
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import kr.ac.unist.bus.arrival.domain.ArrivalStatus
import kr.ac.unist.bus.arrival.domain.Freshness
import kr.ac.unist.bus.config.TrackingProperties
import kr.ac.unist.bus.tracking.application.TrackingSubscriptionConflictException
import kr.ac.unist.bus.tracking.application.TrackingSubscriptionValidationException
import kr.ac.unist.bus.tracking.domain.PushEnvironment
import kr.ac.unist.bus.tracking.domain.TrackedArrival
import kr.ac.unist.bus.tracking.domain.TrackingPlatform
import kr.ac.unist.bus.tracking.domain.TrackingState
import kr.ac.unist.bus.tracking.domain.TrackingSubscription
import kr.ac.unist.bus.tracking.security.EncryptedPushTarget
import kr.ac.unist.bus.tracking.security.PushTargetCipher
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Repository
@ConditionalOnProperty(
    prefix = "unist-bus.tracking",
    name = ["storage"],
    havingValue = "firestore",
)
class FirestoreTrackingSubscriptionRepository(
    firestore: Firestore,
    properties: TrackingProperties,
    private val cipher: PushTargetCipher,
) : TrackingSubscriptionRepository {
    private val collection = firestore.collection(properties.firestoreCollection)

    override fun findById(trackingSessionId: UUID): TrackingSubscription? {
        val document = collection.document(trackingSessionId.toString())
            .get()
            .awaitRepositoryResult()
        return if (document.exists()) document.toDomain(cipher) else null
    }

    override fun findAllActive(): List<TrackingSubscription> =
        collection.whereEqualTo("state", TrackingState.ACTIVE.name)
            .get()
            .awaitRepositoryResult()
            .documents
            .map { document -> document.toDomain(cipher) }

    override fun mutate(
        trackingSessionId: UUID,
        mutation: (TrackingSubscription?) -> TrackingSubscription?,
    ): TrackingSubscription? {
        val reference = collection.document(trackingSessionId.toString())
        return reference.firestore.runTransaction { transaction ->
            val snapshot = transaction.get(reference).get()
            val current = if (snapshot.exists()) snapshot.toDomain(cipher) else null
            val proposed = mutation(current)
            if (proposed == null) {
                FirestoreMutationResult(null)
            } else {
                val stored = proposed.copy(revision = (current?.revision ?: -1) + 1)
                transaction.set(reference, stored.toDocument())
                FirestoreMutationResult(stored)
            }
        }.awaitTransactionResult().subscription
    }

    override fun compareAndMutate(
        trackingSessionId: UUID,
        expectedRevision: Long,
        mutation: (TrackingSubscription) -> TrackingSubscription?,
    ): TrackingSubscription? {
        val reference = collection.document(trackingSessionId.toString())
        return reference.firestore.runTransaction { transaction ->
            val snapshot = transaction.get(reference).get()
            if (!snapshot.exists()) {
                FirestoreMutationResult(null)
            } else {
                val current = snapshot.toDomain(cipher)
                if (current.revision != expectedRevision) {
                    FirestoreMutationResult(null)
                } else {
                    val proposed = mutation(current)
                    if (proposed == null) {
                        FirestoreMutationResult(null)
                    } else {
                        val stored = proposed.copy(revision = current.revision + 1)
                        transaction.set(reference, stored.toDocument())
                        FirestoreMutationResult(stored)
                    }
                }
            }
        }.awaitTransactionResult().subscription
    }
}

private data class FirestoreMutationResult(
    val subscription: TrackingSubscription?,
)

private fun TrackingSubscription.toDocument(): Map<String, Any?> =
    mapOf(
        "trackingSessionId" to trackingSessionId.toString(),
        "installationIdentifier" to installationIdentifier,
        "platform" to platform.name,
        "pushTargetCiphertext" to pushTarget.ciphertext,
        "pushTargetHash" to pushTarget.hash,
        "pushEnvironment" to pushEnvironment.name,
        "tripId" to tripId,
        "routeId" to routeId,
        "routeName" to routeName,
        "stopId" to stopId,
        "initialEstimatedArrivalAt" to initialEstimatedArrivalAt.toTimestamp(),
        "expiresAt" to expiresAt.toTimestamp(),
        "state" to state.name,
        "createdAt" to createdAt.toTimestamp(),
        "updatedAt" to updatedAt.toTimestamp(),
        "lastMatchedArrival" to lastMatchedArrival?.toDocument(),
        "lastDeliveredEta" to lastDeliveredEta.toTimestamp(),
        "lastDeliveredStatus" to lastDeliveredStatus?.name,
        "lastDeliveredFreshness" to lastDeliveredFreshness?.name,
        "lastDeliveredAt" to lastDeliveredAt?.toTimestamp(),
        "deliveryClaimId" to deliveryClaimId?.toString(),
        "deliveryClaimUntil" to deliveryClaimUntil?.toTimestamp(),
        "revision" to revision,
    )

private fun TrackedArrival.toDocument(): Map<String, Any> =
    mapOf(
        "tripId" to tripId,
        "routeId" to routeId,
        "routeName" to routeName,
        "stopId" to stopId,
        "stopName" to stopName,
        "destination" to destination,
        "estimatedArrivalAt" to estimatedArrivalAt.toTimestamp(),
        "observedAt" to observedAt.toTimestamp(),
        "staleAt" to staleAt.toTimestamp(),
        "status" to status.name,
        "freshness" to freshness.name,
    )

private fun DocumentSnapshot.toDomain(cipher: PushTargetCipher): TrackingSubscription {
    val matched = get("lastMatchedArrival") as? Map<*, *>
    return TrackingSubscription(
        trackingSessionId = UUID.fromString(requireString("trackingSessionId")),
        installationIdentifier = installationIdentifier(cipher),
        platform = enumValueOf(requireString("platform")),
        pushTarget = EncryptedPushTarget(
            ciphertext = requireString("pushTargetCiphertext"),
            hash = requireString("pushTargetHash"),
        ),
        pushEnvironment = enumValueOf(requireString("pushEnvironment")),
        tripId = requireString("tripId"),
        routeId = requireString("routeId"),
        routeName = requireString("routeName"),
        stopId = requireString("stopId"),
        initialEstimatedArrivalAt = requireTimestamp("initialEstimatedArrivalAt"),
        expiresAt = requireTimestamp("expiresAt"),
        state = enumValueOf(requireString("state")),
        createdAt = requireTimestamp("createdAt"),
        updatedAt = requireTimestamp("updatedAt"),
        lastMatchedArrival = matched?.toTrackedArrival(),
        lastDeliveredEta = requireTimestamp("lastDeliveredEta"),
        lastDeliveredStatus = getString("lastDeliveredStatus")?.let(ArrivalStatus::valueOf),
        lastDeliveredFreshness = getString("lastDeliveredFreshness")?.let(Freshness::valueOf),
        lastDeliveredAt = getTimestamp("lastDeliveredAt")?.toInstant(),
        deliveryClaimId = getString("deliveryClaimId")?.let(UUID::fromString),
        deliveryClaimUntil = getTimestamp("deliveryClaimUntil")?.toInstant(),
        revision = getLong("revision") ?: 0,
    )
}

private fun DocumentSnapshot.installationIdentifier(cipher: PushTargetCipher): String =
    getString("installationIdentifier")
        ?: getString("installationId")?.let(cipher::installationIdentifier)
        ?: error("Firestore 문서에 installationIdentifier 값이 없습니다.")

private fun Map<*, *>.toTrackedArrival(): TrackedArrival =
    TrackedArrival(
        tripId = requireString("tripId"),
        routeId = requireString("routeId"),
        routeName = requireString("routeName"),
        stopId = requireString("stopId"),
        stopName = requireString("stopName"),
        destination = requireString("destination"),
        estimatedArrivalAt = requireTimestamp("estimatedArrivalAt"),
        observedAt = requireTimestamp("observedAt"),
        staleAt = requireTimestamp("staleAt"),
        status = ArrivalStatus.valueOf(requireString("status")),
        freshness = Freshness.valueOf(requireString("freshness")),
    )

private fun DocumentSnapshot.requireString(name: String): String =
    requireNotNull(getString(name)) { "Firestore 문서에 $name 값이 없습니다." }

private fun DocumentSnapshot.requireTimestamp(name: String): Instant =
    requireNotNull(getTimestamp(name)) { "Firestore 문서에 $name 값이 없습니다." }.toInstant()

private fun Map<*, *>.requireString(name: String): String =
    requireNotNull(this[name] as? String) { "Firestore 문서에 $name 값이 없습니다." }

private fun Map<*, *>.requireTimestamp(name: String): Instant =
    requireNotNull(this[name] as? Timestamp) { "Firestore 문서에 $name 값이 없습니다." }.toInstant()

private fun Instant.toTimestamp(): Timestamp = Timestamp.ofTimeSecondsAndNanos(epochSecond, nano)

private fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

internal fun <T> ApiFuture<T>.awaitTransactionResult(): T =
    awaitRepositoryResult(preservesDomainFailures = true)

private fun <T> ApiFuture<T>.awaitRepositoryResult(
    preservesDomainFailures: Boolean = false,
): T =
    try {
        get(REPOSITORY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw TrackingRepositoryException()
    } catch (_: TimeoutException) {
        throw TrackingRepositoryException()
    } catch (failure: ExecutionException) {
        when (val cause = failure.cause) {
            is TrackingSubscriptionConflictException ->
                if (preservesDomainFailures) throw cause else throw TrackingRepositoryException()
            is TrackingSubscriptionValidationException ->
                if (preservesDomainFailures) throw cause else throw TrackingRepositoryException()
            else -> throw TrackingRepositoryException()
        }
    }

internal class TrackingRepositoryException :
    RuntimeException("추적 저장소 작업에 실패했습니다.")

private const val REPOSITORY_TIMEOUT_SECONDS = 8L
