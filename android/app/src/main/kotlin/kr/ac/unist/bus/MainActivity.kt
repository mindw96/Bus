package kr.ac.unist.bus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kr.ac.unist.bus.data.HttpArrivalRepository
import kr.ac.unist.bus.model.StopArrival
import kr.ac.unist.bus.notification.TrackingNotificationController
import kr.ac.unist.bus.push.FcmRegistrationCoordinator
import kr.ac.unist.bus.push.PushDependencies
import kr.ac.unist.bus.ui.ArrivalsScreen
import kr.ac.unist.bus.ui.ArrivalsViewModel
import kr.ac.unist.bus.ui.theme.UNISTBusTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = HttpArrivalRepository(BuildConfig.UNIST_BUS_API_BASE_URL)
        val trackingStateStore = PushDependencies.stateStore(this)
        val notificationController = TrackingNotificationController(this, trackingStateStore)
        val subscriptionScheduler = PushDependencies.subscriptionScheduler(this)
        if (BuildConfig.FCM_ENABLED) {
            FcmRegistrationCoordinator.registerIfEnabled()
        }

        setContent {
            val factory = remember {
                ArrivalsViewModel.factory(
                    repository,
                    notificationController,
                    subscriptionScheduler,
                )
            }
            val arrivalsViewModel: ArrivalsViewModel = viewModel(factory = factory)
            val state by arrivalsViewModel.uiState.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            var pendingArrival by remember { mutableStateOf<StopArrival?>(null) }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted ->
                val arrival = pendingArrival
                pendingArrival = null
                if (granted && arrival != null) {
                    arrivalsViewModel.track(arrival)
                } else if (!granted) {
                    arrivalsViewModel.notificationPermissionDenied()
                }
            }

            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                arrivalsViewModel.syncTrackingState()
                arrivalsViewModel.refresh()
            }

            LaunchedEffect(arrivalsViewModel) {
                this@MainActivity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) {
                        delay(30_000)
                        arrivalsViewModel.refresh()
                    }
                }
            }

            LaunchedEffect(state.message) {
                val message = state.message ?: return@LaunchedEffect
                snackbarHostState.showSnackbar(message)
                arrivalsViewModel.consumeMessage()
            }

            UNISTBusTheme {
                ArrivalsScreen(
                    state = state,
                    snackbarHostState = snackbarHostState,
                    onRefresh = arrivalsViewModel::refresh,
                    onToggleTracking = { item ->
                        if (
                            state.trackedStopId == item.stop.id &&
                            state.trackedRouteId == item.arrival.routeId
                        ) {
                            arrivalsViewModel.stopTracking()
                        } else if (needsNotificationPermission()) {
                            pendingArrival = item
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            arrivalsViewModel.track(item)
                        }
                    },
                )
            }
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
}
