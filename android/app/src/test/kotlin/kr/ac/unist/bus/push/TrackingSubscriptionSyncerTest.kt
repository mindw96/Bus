package kr.ac.unist.bus.push

import java.io.IOException
import java.time.Instant
import kr.ac.unist.bus.notification.PersistedTrackingState
import kr.ac.unist.bus.notification.TrackingSubscriptionState
import kr.ac.unist.bus.notification.TrackingSubscriptionStateAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingSubscriptionSyncerTest {
    @Test
    fun disabledSyncerPerformsNoNetworkWork() {
        val state = FakeStateAccess(activeState = pendingState())
        val client = FakeClient()
        val syncer = TrackingSubscriptionSyncer(
            stateStore = state,
            client = client,
            pushEnvironment = PushEnvironment.DEVELOPMENT,
            isEnabled = false,
        )

        assertTrue(syncer.syncPending())
        assertEquals(0, client.putCalls)
        assertEquals(0, client.deleteCalls)
        assertEquals(TrackingSubscriptionState.PENDING_UPSERT, state.activeState?.subscriptionState)
    }

    @Test
    fun failedRequestRemainsPendingAndRequestsRetry() {
        val state = FakeStateAccess(activeState = pendingState())
        val client = FakeClient(failPut = true)
        val syncer = TrackingSubscriptionSyncer(
            stateStore = state,
            client = client,
            pushEnvironment = PushEnvironment.DEVELOPMENT,
        )

        assertFalse(syncer.syncPending())
        assertEquals(TrackingSubscriptionState.PENDING_UPSERT, state.activeState?.subscriptionState)
    }

    @Test
    fun successfulDeleteAndUpsertClearAllPendingWork() {
        val state = FakeStateAccess(
            activeState = pendingState(),
            pendingDeletes = mutableSetOf("old-session"),
        )
        val client = FakeClient()
        val syncer = TrackingSubscriptionSyncer(
            stateStore = state,
            client = client,
            pushEnvironment = PushEnvironment.DEVELOPMENT,
        )

        assertTrue(syncer.syncPending())
        assertEquals(1, client.putCalls)
        assertEquals(1, client.deleteCalls)
        assertEquals(TrackingSubscriptionState.SYNCED, state.activeState?.subscriptionState)
        assertTrue(state.pendingDeleteSessionIds.isEmpty())
    }

    private fun pendingState(): PersistedTrackingState = PersistedTrackingState(
        trackingSessionId = "session-1",
        stopId = "40233",
        routeId = "route-713",
        routeName = "713",
        tripId = "trip-713",
        estimatedArrivalAt = Instant.parse("2026-07-24T05:30:00Z"),
        expiresAt = Instant.parse("2026-07-24T05:45:00Z"),
        lastObservedAt = Instant.parse("2026-07-24T05:22:00Z"),
        installationId = "fid-1234567890",
        subscriptionState = TrackingSubscriptionState.PENDING_UPSERT,
    )
}

private class FakeStateAccess(
    override var activeState: PersistedTrackingState?,
    private val pendingDeletes: MutableSet<String> = mutableSetOf(),
) : TrackingSubscriptionStateAccess {
    override val pendingDeleteSessionIds: Set<String>
        get() = pendingDeletes.toSet()

    override fun markUpsertSynced(
        expected: PersistedTrackingState,
        responseExpiresAt: Instant,
    ) {
        if (activeState == expected) {
            activeState = expected.copy(
                expiresAt = responseExpiresAt,
                subscriptionState = TrackingSubscriptionState.SYNCED,
            )
        }
    }

    override fun markDeleteSynced(trackingSessionId: String) {
        pendingDeletes -= trackingSessionId
    }
}

private class FakeClient(
    private val failPut: Boolean = false,
) : TrackingSubscriptionClient {
    var putCalls = 0
    var deleteCalls = 0

    override fun put(request: TrackingSubscriptionRequest): TrackingSubscriptionResponse {
        putCalls += 1
        if (failPut) {
            throw IOException("offline")
        }
        return TrackingSubscriptionResponse(
            trackingSessionId = request.trackingSessionId,
            state = "ACTIVE",
            updatedAt = Instant.parse("2026-07-24T05:23:00Z"),
            expiresAt = request.expiresAt,
        )
    }

    override fun delete(trackingSessionId: String) {
        deleteCalls += 1
    }
}
