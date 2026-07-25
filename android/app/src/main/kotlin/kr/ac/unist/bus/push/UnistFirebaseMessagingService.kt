package kr.ac.unist.bus.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kr.ac.unist.bus.notification.TrackingNotificationController

class UnistFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        val stateStore = PushDependencies.stateStore(this)
        stateStore.updateInstallationId(installationId)
        PushDependencies.subscriptionScheduler(this).enqueue()
    }

    override fun onUnregistered(installationId: String) {
        PushDependencies.stateStore(this).clearInstallationId(installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = ArrivalPushPayloadDecoder.decode(message.data) ?: return
        val stateStore = PushDependencies.stateStore(this)
        TrackingNotificationController(this, stateStore).applyRemote(payload)
        PushDependencies.subscriptionScheduler(this).enqueue()
    }

    override fun onDeletedMessages() {
        PushDependencies.subscriptionScheduler(this).enqueue()
    }
}
