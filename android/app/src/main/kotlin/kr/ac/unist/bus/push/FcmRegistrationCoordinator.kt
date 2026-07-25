package kr.ac.unist.bus.push

import com.google.firebase.messaging.FirebaseMessaging
import kr.ac.unist.bus.BuildConfig

object FcmRegistrationCoordinator {
    fun registerIfEnabled() {
        if (!BuildConfig.FCM_ENABLED) {
            return
        }
        FirebaseMessaging.getInstance().register()
    }
}
