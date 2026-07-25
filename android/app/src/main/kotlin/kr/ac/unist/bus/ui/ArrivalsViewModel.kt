package kr.ac.unist.bus.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kr.ac.unist.bus.data.ArrivalRepository
import kr.ac.unist.bus.model.StopArrival
import kr.ac.unist.bus.notification.PersistedTrackingState
import kr.ac.unist.bus.notification.TrackingNotificationController
import kr.ac.unist.bus.push.TrackingSubscriptionScheduler
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArrivalsUiState(
    val arrivals: List<StopArrival> = emptyList(),
    val generatedAt: Instant? = null,
    val isRefreshing: Boolean = false,
    val trackingSessionId: String? = null,
    val trackedStopId: String? = null,
    val trackedRouteId: String? = null,
    val trackedTripId: String? = null,
    val message: String? = null,
)

class ArrivalsViewModel(
    private val repository: ArrivalRepository,
    private val notificationController: TrackingNotificationController,
    private val subscriptionScheduler: TrackingSubscriptionScheduler,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        ArrivalsUiState().withTracking(notificationController.trackedState),
    )
    val uiState: StateFlow<ArrivalsUiState> = mutableUiState.asStateFlow()
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) {
            return
        }
        refreshJob = viewModelScope.launch {
            mutableUiState.update { it.copy(isRefreshing = true) }
            try {
                val snapshot = repository.fetch()
                val trackedState = notificationController.trackedState
                snapshot.arrivals
                    .firstOrNull { item -> trackedState?.matches(item) == true }
                    ?.let { trackedArrival ->
                        runCatching { notificationController.refresh(trackedArrival) }
                    }

                mutableUiState.update {
                    it.copy(
                        arrivals = snapshot.arrivals,
                        generatedAt = snapshot.generatedAt,
                        isRefreshing = false,
                    ).withTracking(notificationController.trackedState)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(
                        isRefreshing = false,
                        message = "실시간 버스 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.",
                    )
                }
            } finally {
                subscriptionScheduler.enqueue()
            }
        }
    }

    fun track(item: StopArrival) {
        runCatching {
            notificationController.start(item)
        }.onSuccess {
            mutableUiState.update {
                it.copy(
                    message = "${item.arrival.routeName}번을 잠금화면에서 표시합니다.",
                ).withTracking(notificationController.trackedState)
            }
            syncSubscriptions()
        }.onFailure {
            mutableUiState.update {
                it.copy(message = "알림을 표시하지 못했습니다. 알림 설정을 확인해 주세요.")
            }
        }
    }

    fun stopTracking() {
        notificationController.stop()
        mutableUiState.update {
            it.copy(
                message = "잠금화면 추적을 중단했습니다.",
            ).withTracking(null)
        }
        syncSubscriptions()
    }

    fun syncTrackingState() {
        if (notificationController.reconcileTrackingAvailability()) {
            syncSubscriptions()
        }
        mutableUiState.update {
            it.withTracking(notificationController.trackedState)
        }
    }

    fun notificationPermissionDenied() {
        mutableUiState.update {
            it.copy(message = "잠금화면에서 보려면 알림 권한을 허용해 주세요.")
        }
    }

    fun consumeMessage() {
        mutableUiState.update { it.copy(message = null) }
    }

    private fun syncSubscriptions() {
        subscriptionScheduler.enqueue()
    }

    companion object {
        fun factory(
            repository: ArrivalRepository,
            notificationController: TrackingNotificationController,
            subscriptionScheduler: TrackingSubscriptionScheduler,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ArrivalsViewModel(repository, notificationController, subscriptionScheduler) as T
        }
    }
}

private fun ArrivalsUiState.withTracking(
    trackingState: PersistedTrackingState?,
): ArrivalsUiState = copy(
    trackingSessionId = trackingState?.trackingSessionId,
    trackedStopId = trackingState?.stopId,
    trackedRouteId = trackingState?.routeId,
    trackedTripId = trackingState?.tripId,
)
