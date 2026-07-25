package kr.ac.unist.bus.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kr.ac.unist.bus.push.PushDependencies

class StopTrackingReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == TrackingNotificationController.ACTION_STOP_TRACKING) {
            val stateStore = PushDependencies.stateStore(context)
            TrackingNotificationController(context, stateStore).stop()
            PushDependencies.subscriptionScheduler(context).enqueue()
        }
    }
}
