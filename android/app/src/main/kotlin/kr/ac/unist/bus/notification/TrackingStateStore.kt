package kr.ac.unist.bus.notification

import android.content.Context
import androidx.core.content.edit
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kr.ac.unist.bus.model.StopArrival

enum class TrackingSubscriptionState {
    PENDING_UPSERT,
    SYNCED,
}

data class PersistedTrackingState(
    val trackingSessionId: String,
    val stopId: String,
    val routeId: String,
    val routeName: String,
    val tripId: String,
    val estimatedArrivalAt: Instant,
    val expiresAt: Instant,
    val lastObservedAt: Instant,
    val installationId: String?,
    val subscriptionState: TrackingSubscriptionState,
) {
    fun matches(item: StopArrival): Boolean =
        stopId == item.stop.id && routeId == item.arrival.routeId
}

interface TrackingSubscriptionStateAccess {
    val activeState: PersistedTrackingState?
    val pendingDeleteSessionIds: Set<String>

    fun markUpsertSynced(
        expected: PersistedTrackingState,
        responseExpiresAt: Instant,
    )

    fun markDeleteSynced(trackingSessionId: String)
}

class TrackingStateStore(
    context: Context,
) : TrackingSubscriptionStateAccess {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override val activeState: PersistedTrackingState?
        get() = synchronized(PROCESS_LOCK) { readActiveState() }

    val installationId: String?
        get() = synchronized(PROCESS_LOCK) {
            preferences.getString(KEY_INSTALLATION_ID, null)
        }

    override val pendingDeleteSessionIds: Set<String>
        get() = synchronized(PROCESS_LOCK) {
            preferences.getStringSet(KEY_PENDING_DELETE_SESSION_IDS, emptySet())
                .orEmpty()
                .toSet()
        }

    fun beginTracking(item: StopArrival): PersistedTrackingState = synchronized(PROCESS_LOCK) {
        val previousSessionId = readActiveState()?.trackingSessionId
        val state = PersistedTrackingState(
            trackingSessionId = UUID.randomUUID().toString(),
            stopId = item.stop.id,
            routeId = item.arrival.routeId,
            routeName = item.arrival.routeName,
            tripId = item.arrival.tripId,
            estimatedArrivalAt = item.arrival.estimatedArrivalAt,
            expiresAt = item.arrival.estimatedArrivalAt.plus(TRACKING_GRACE_PERIOD),
            lastObservedAt = item.arrival.observedAt,
            installationId = preferences.getString(KEY_INSTALLATION_ID, null),
            subscriptionState = TrackingSubscriptionState.PENDING_UPSERT,
        )

        preferences.edit {
            previousSessionId?.let { addPendingDelete(this, it) }
            writeActiveState(this, state)
        }
        state
    }

    fun updateFromLocal(item: StopArrival): PersistedTrackingState? = synchronized(PROCESS_LOCK) {
        val current = readActiveState() ?: return@synchronized null
        val updated = updatedFromLocal(current, item) ?: return@synchronized null
        if (updated != current) {
            preferences.edit { writeActiveState(this, updated) }
        }
        updated
    }

    fun updateFromRemote(
        trackingSessionId: String,
        tripId: String,
        estimatedArrivalAt: Instant,
        observedAt: Instant,
    ): PersistedTrackingState? = synchronized(PROCESS_LOCK) {
        val current = readActiveState() ?: return@synchronized null
        if (
            current.trackingSessionId != trackingSessionId ||
            observedAt.isBefore(current.lastObservedAt)
        ) {
            return@synchronized null
        }

        val updated = current.copy(
            tripId = tripId,
            estimatedArrivalAt = estimatedArrivalAt,
            expiresAt = estimatedArrivalAt.plus(TRACKING_GRACE_PERIOD),
            lastObservedAt = observedAt,
        )
        preferences.edit { writeActiveState(this, updated) }
        updated
    }

    fun updateInstallationId(newInstallationId: String) = synchronized(PROCESS_LOCK) {
        if (newInstallationId.isBlank()) {
            return@synchronized
        }

        val currentInstallationId = preferences.getString(KEY_INSTALLATION_ID, null)
        if (currentInstallationId == newInstallationId) {
            return@synchronized
        }

        val current = readActiveState()
        preferences.edit {
            putString(KEY_INSTALLATION_ID, newInstallationId)
            if (current != null) {
                writeActiveState(
                    this,
                    current.copy(
                        installationId = newInstallationId,
                        subscriptionState = TrackingSubscriptionState.PENDING_UPSERT,
                    ),
                )
            }
        }
    }

    fun clearInstallationId(unregisteredInstallationId: String) = synchronized(PROCESS_LOCK) {
        if (preferences.getString(KEY_INSTALLATION_ID, null) != unregisteredInstallationId) {
            return@synchronized
        }

        val current = readActiveState()
        preferences.edit {
            remove(KEY_INSTALLATION_ID)
            if (current != null) {
                writeActiveState(
                    this,
                    current.copy(
                        installationId = null,
                        subscriptionState = TrackingSubscriptionState.PENDING_UPSERT,
                    ),
                )
            }
        }
    }

    fun requestStop(): String? = synchronized(PROCESS_LOCK) {
        val current = readActiveState() ?: return@synchronized null
        preferences.edit {
            addPendingDelete(this, current.trackingSessionId)
            clearActiveState(this)
        }
        current.trackingSessionId
    }

    fun finishFromRemote(trackingSessionId: String): Boolean = synchronized(PROCESS_LOCK) {
        val current = readActiveState() ?: return@synchronized false
        if (current.trackingSessionId != trackingSessionId) {
            return@synchronized false
        }

        preferences.edit {
            clearActiveState(this)
            removePendingDelete(this, trackingSessionId)
        }
        true
    }

    override fun markUpsertSynced(
        expected: PersistedTrackingState,
        responseExpiresAt: Instant,
    ) = synchronized(PROCESS_LOCK) {
        val current = readActiveState() ?: return@synchronized
        if (!canMarkUpsertSynced(current, expected)) {
            return@synchronized
        }

        preferences.edit {
            writeActiveState(
                this,
                current.copy(
                    expiresAt = responseExpiresAt,
                    subscriptionState = TrackingSubscriptionState.SYNCED,
                ),
            )
        }
    }

    override fun markDeleteSynced(trackingSessionId: String) = synchronized(PROCESS_LOCK) {
        preferences.edit { removePendingDelete(this, trackingSessionId) }
    }

    private fun readActiveState(): PersistedTrackingState? {
        val trackingSessionId = preferences.getString(KEY_TRACKING_SESSION_ID, null) ?: return null
        val stopId = preferences.getString(KEY_STOP_ID, null) ?: return null
        val routeId = preferences.getString(KEY_ROUTE_ID, null) ?: return null
        val routeName = preferences.getString(KEY_ROUTE_NAME, null) ?: return null
        val tripId = preferences.getString(KEY_TRIP_ID, null) ?: return null
        val estimatedArrivalAt = preferences.instant(KEY_ESTIMATED_ARRIVAL_AT) ?: return null
        val expiresAt = preferences.instant(KEY_EXPIRES_AT) ?: return null
        val lastObservedAt = preferences.instant(KEY_LAST_OBSERVED_AT) ?: return null
        val subscriptionState = preferences.getString(KEY_SUBSCRIPTION_STATE, null)
            ?.let { stored -> TrackingSubscriptionState.entries.firstOrNull { it.name == stored } }
            ?: TrackingSubscriptionState.PENDING_UPSERT

        return PersistedTrackingState(
            trackingSessionId = trackingSessionId,
            stopId = stopId,
            routeId = routeId,
            routeName = routeName,
            tripId = tripId,
            estimatedArrivalAt = estimatedArrivalAt,
            expiresAt = expiresAt,
            lastObservedAt = lastObservedAt,
            installationId = preferences.getString(KEY_INSTALLATION_ID, null),
            subscriptionState = subscriptionState,
        )
    }

    private fun writeActiveState(
        editor: android.content.SharedPreferences.Editor,
        state: PersistedTrackingState,
    ) {
        editor
            .putString(KEY_TRACKING_SESSION_ID, state.trackingSessionId)
            .putString(KEY_STOP_ID, state.stopId)
            .putString(KEY_ROUTE_ID, state.routeId)
            .putString(KEY_ROUTE_NAME, state.routeName)
            .putString(KEY_TRIP_ID, state.tripId)
            .putString(KEY_ESTIMATED_ARRIVAL_AT, state.estimatedArrivalAt.toString())
            .putString(KEY_EXPIRES_AT, state.expiresAt.toString())
            .putString(KEY_LAST_OBSERVED_AT, state.lastObservedAt.toString())
            .putString(KEY_SUBSCRIPTION_STATE, state.subscriptionState.name)
    }

    private fun clearActiveState(editor: android.content.SharedPreferences.Editor) {
        ACTIVE_KEYS.forEach(editor::remove)
    }

    private fun addPendingDelete(
        editor: android.content.SharedPreferences.Editor,
        trackingSessionId: String,
    ) {
        val pending = preferences.getStringSet(KEY_PENDING_DELETE_SESSION_IDS, emptySet())
            .orEmpty()
            .toMutableSet()
        pending += trackingSessionId
        editor.putStringSet(KEY_PENDING_DELETE_SESSION_IDS, pending)
    }

    private fun removePendingDelete(
        editor: android.content.SharedPreferences.Editor,
        trackingSessionId: String,
    ) {
        val pending = preferences.getStringSet(KEY_PENDING_DELETE_SESSION_IDS, emptySet())
            .orEmpty()
            .toMutableSet()
        pending -= trackingSessionId
        editor.putStringSet(KEY_PENDING_DELETE_SESSION_IDS, pending)
    }

    private fun android.content.SharedPreferences.instant(key: String): Instant? =
        getString(key, null)?.let { stored ->
            runCatching { Instant.parse(stored) }.getOrNull()
        }

    companion object {
        const val PREFERENCES_NAME = "tracking_state"
        internal val TRACKING_GRACE_PERIOD = Duration.ofMinutes(15)
        private val PROCESS_LOCK = Any()

        private const val KEY_TRACKING_SESSION_ID = "tracking_session_id"
        private const val KEY_STOP_ID = "tracked_stop_id"
        private const val KEY_ROUTE_ID = "tracked_route_id"
        private const val KEY_ROUTE_NAME = "tracked_route_name"
        private const val KEY_TRIP_ID = "tracked_trip_id"
        private const val KEY_ESTIMATED_ARRIVAL_AT = "tracked_estimated_arrival_at"
        private const val KEY_EXPIRES_AT = "tracked_expires_at"
        private const val KEY_LAST_OBSERVED_AT = "tracked_last_observed_at"
        private const val KEY_INSTALLATION_ID = "firebase_installation_id"
        private const val KEY_SUBSCRIPTION_STATE = "tracking_subscription_state"
        private const val KEY_PENDING_DELETE_SESSION_IDS = "pending_delete_session_ids"

        private val ACTIVE_KEYS = listOf(
            KEY_TRACKING_SESSION_ID,
            KEY_STOP_ID,
            KEY_ROUTE_ID,
            KEY_ROUTE_NAME,
            KEY_TRIP_ID,
            KEY_ESTIMATED_ARRIVAL_AT,
            KEY_EXPIRES_AT,
            KEY_LAST_OBSERVED_AT,
            KEY_SUBSCRIPTION_STATE,
        )
    }
}

internal fun updatedFromLocal(
    current: PersistedTrackingState,
    item: StopArrival,
): PersistedTrackingState? {
    if (!current.matches(item) || item.arrival.observedAt.isBefore(current.lastObservedAt)) {
        return null
    }

    val subscriptionChanged =
        current.tripId != item.arrival.tripId ||
            current.routeName != item.arrival.routeName ||
            current.estimatedArrivalAt != item.arrival.estimatedArrivalAt
    return current.copy(
        tripId = item.arrival.tripId,
        routeName = item.arrival.routeName,
        estimatedArrivalAt = item.arrival.estimatedArrivalAt,
        expiresAt = item.arrival.estimatedArrivalAt.plus(TrackingStateStore.TRACKING_GRACE_PERIOD),
        lastObservedAt = item.arrival.observedAt,
        subscriptionState = if (subscriptionChanged) {
            TrackingSubscriptionState.PENDING_UPSERT
        } else {
            current.subscriptionState
        },
    )
}

internal fun canMarkUpsertSynced(
    current: PersistedTrackingState,
    expected: PersistedTrackingState,
): Boolean =
    current.trackingSessionId == expected.trackingSessionId &&
        current.installationId == expected.installationId &&
        current.tripId == expected.tripId &&
        current.estimatedArrivalAt == expected.estimatedArrivalAt &&
        current.lastObservedAt == expected.lastObservedAt &&
        current.subscriptionState == TrackingSubscriptionState.PENDING_UPSERT
