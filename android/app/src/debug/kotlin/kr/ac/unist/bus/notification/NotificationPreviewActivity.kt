package kr.ac.unist.bus.notification

import android.app.Activity
import android.os.Bundle
import java.time.Instant
import kr.ac.unist.bus.model.Arrival
import kr.ac.unist.bus.model.ArrivalStatus
import kr.ac.unist.bus.model.BusStop
import kr.ac.unist.bus.model.StopArrival

class NotificationPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val now = Instant.now()
        val estimatedArrivalAt = now.plusSeconds(PREVIEW_SECONDS)
        TrackingNotificationController(this).start(
            StopArrival(
                stop = BusStop("40235", "울산과학기술원(종점)"),
                arrival = Arrival(
                    tripId = "debug-preview-trip",
                    routeId = "debug-preview-route-1115",
                    routeName = "1115",
                    destination = "UNIST",
                    scheduledArrivalAt = estimatedArrivalAt,
                    estimatedArrivalAt = estimatedArrivalAt,
                    observedAt = now,
                    staleAt = estimatedArrivalAt.plusSeconds(60),
                    status = ArrivalStatus.SCHEDULED,
                ),
            ),
        )
        finish()
    }

    private companion object {
        const val PREVIEW_SECONDS = 7 * 60 + 28L
    }
}
