package kr.ac.unist.bus.push

import kr.ac.unist.bus.notification.TrackingSubscriptionStateAccess
import kr.ac.unist.bus.notification.TrackingSubscriptionState

class TrackingSubscriptionSyncer(
    private val stateStore: TrackingSubscriptionStateAccess,
    private val client: TrackingSubscriptionClient,
    private val pushEnvironment: PushEnvironment,
    private val isEnabled: Boolean = true,
) {
    fun syncPending(): Boolean {
        if (!isEnabled) {
            return true
        }
        var allRequestsSucceeded = true

        stateStore.pendingDeleteSessionIds.forEach { trackingSessionId ->
            runCatching { client.delete(trackingSessionId) }
                .onSuccess { stateStore.markDeleteSynced(trackingSessionId) }
                .onFailure { allRequestsSucceeded = false }
        }

        val active = stateStore.activeState
        if (active?.subscriptionState == TrackingSubscriptionState.PENDING_UPSERT) {
            val request = TrackingSubscriptionRequest.from(active, pushEnvironment)
            if (request != null) {
                runCatching { client.put(request) }
                    .onSuccess { response ->
                        if (response.trackingSessionId == active.trackingSessionId) {
                            stateStore.markUpsertSynced(active, response.expiresAt)
                        } else {
                            allRequestsSucceeded = false
                        }
                    }
                    .onFailure { allRequestsSucceeded = false }
            }
        }

        val pendingUpsertWithTarget = stateStore.activeState?.let { latest ->
            latest.subscriptionState == TrackingSubscriptionState.PENDING_UPSERT &&
                !latest.installationId.isNullOrBlank()
        } == true
        return allRequestsSucceeded &&
            stateStore.pendingDeleteSessionIds.isEmpty() &&
            !pendingUpsertWithTarget
    }
}
