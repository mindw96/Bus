package kr.ac.unist.bus.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopTrackingReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == TrackingNotificationController.ACTION_STOP_TRACKING) {
            TrackingNotificationController(context).stop()
        }
    }
}
