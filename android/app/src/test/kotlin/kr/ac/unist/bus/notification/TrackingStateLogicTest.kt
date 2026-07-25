package kr.ac.unist.bus.notification

import java.time.Instant
import kr.ac.unist.bus.model.Arrival
import kr.ac.unist.bus.model.ArrivalStatus
import kr.ac.unist.bus.model.BusStop
import kr.ac.unist.bus.model.StopArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingStateLogicTest {
    private val observedAt = Instant.parse("2026-07-24T05:22:00Z")
    private val estimatedAt = Instant.parse("2026-07-24T05:30:00Z")

    @Test
    fun identicalLocalRefreshDoesNotReopenSyncedSubscription() {
        val current = state(subscriptionState = TrackingSubscriptionState.SYNCED)

        val updated = updatedFromLocal(current, item())

        assertEquals(current, updated)
    }

    @Test
    fun newerObservationWithoutContractChangesStaysSynced() {
        val current = state(subscriptionState = TrackingSubscriptionState.SYNCED)
        val newerObservedAt = observedAt.plusSeconds(30)

        val updated = updatedFromLocal(
            current,
            item(observedAt = newerObservedAt),
        )

        assertEquals(newerObservedAt, updated?.lastObservedAt)
        assertEquals(TrackingSubscriptionState.SYNCED, updated?.subscriptionState)
    }

    @Test
    fun changedTripReopensSubscriptionSync() {
        val current = state(subscriptionState = TrackingSubscriptionState.SYNCED)

        val updated = updatedFromLocal(current, item(tripId = "trip-714"))

        assertEquals(TrackingSubscriptionState.PENDING_UPSERT, updated?.subscriptionState)
    }

    @Test
    fun stalePutResponseCannotMarkNewerTripOrObservationSynced() {
        val expected = state()

        assertFalse(canMarkUpsertSynced(expected.copy(tripId = "new-trip"), expected))
        assertFalse(
            canMarkUpsertSynced(
                expected.copy(lastObservedAt = observedAt.plusSeconds(1)),
                expected,
            ),
        )
        assertTrue(canMarkUpsertSynced(expected, expected))
    }

    @Test
    fun revokedPermissionOrExpiryStopsTrackingLocally() {
        val current = state()

        assertTrue(
            shouldStopLocally(
                state = current,
                notificationsAllowed = false,
                now = estimatedAt.minusSeconds(60),
            ),
        )
        assertTrue(
            shouldStopLocally(
                state = current,
                notificationsAllowed = true,
                now = current.expiresAt,
            ),
        )
        assertFalse(
            shouldStopLocally(
                state = current,
                notificationsAllowed = true,
                now = current.expiresAt.minusSeconds(1),
            ),
        )
    }

    private fun state(
        subscriptionState: TrackingSubscriptionState = TrackingSubscriptionState.PENDING_UPSERT,
    ): PersistedTrackingState = PersistedTrackingState(
        trackingSessionId = "session-1",
        stopId = "40233",
        routeId = "route-713",
        routeName = "713",
        tripId = "trip-713",
        estimatedArrivalAt = estimatedAt,
        expiresAt = estimatedAt.plusSeconds(15 * 60),
        lastObservedAt = observedAt,
        installationId = "fid-1234567890",
        subscriptionState = subscriptionState,
    )

    private fun item(
        tripId: String = "trip-713",
        observedAt: Instant = this.observedAt,
    ): StopArrival = StopArrival(
        stop = BusStop("40233", "울산과학기술원(기점)"),
        arrival = Arrival(
            tripId = tripId,
            routeId = "route-713",
            routeName = "713",
            destination = "덕하 차고지",
            scheduledArrivalAt = estimatedAt,
            estimatedArrivalAt = estimatedAt,
            observedAt = observedAt,
            staleAt = observedAt.plusSeconds(90),
            status = ArrivalStatus.APPROACHING,
        ),
    )
}
