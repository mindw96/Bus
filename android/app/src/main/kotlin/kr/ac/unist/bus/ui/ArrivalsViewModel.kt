package kr.ac.unist.bus.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kr.ac.unist.bus.data.ArrivalRepository
import kr.ac.unist.bus.model.StopArrival
import kr.ac.unist.bus.notification.TrackingNotificationController
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
    val trackedTripId: String? = null,
    val message: String? = null,
)

class ArrivalsViewModel(
    private val repository: ArrivalRepository,
    private val notificationController: TrackingNotificationController,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        ArrivalsUiState(trackedTripId = notificationController.trackedTripId),
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
                val trackedTripId = notificationController.trackedTripId

                snapshot.arrivals
                    .firstOrNull { it.arrival.tripId == trackedTripId }
                    ?.let { trackedArrival ->
                        runCatching { notificationController.start(trackedArrival) }
                    }

                mutableUiState.update {
                    it.copy(
                        arrivals = snapshot.arrivals,
                        generatedAt = snapshot.generatedAt,
                        isRefreshing = false,
                        trackedTripId = trackedTripId,
                    )
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
            }
        }
    }

    fun track(item: StopArrival) {
        runCatching {
            notificationController.start(item)
        }.onSuccess {
            mutableUiState.update {
                it.copy(
                    trackedTripId = item.arrival.tripId,
                    message = "${item.arrival.routeName}번을 잠금화면에서 표시합니다.",
                )
            }
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
                trackedTripId = null,
                message = "잠금화면 추적을 중단했습니다.",
            )
        }
    }

    fun syncTrackingState() {
        mutableUiState.update {
            it.copy(trackedTripId = notificationController.trackedTripId)
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

    companion object {
        fun factory(
            repository: ArrivalRepository,
            notificationController: TrackingNotificationController,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ArrivalsViewModel(repository, notificationController) as T
        }
    }
}
