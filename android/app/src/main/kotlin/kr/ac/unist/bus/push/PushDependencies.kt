package kr.ac.unist.bus.push

import android.content.Context
import kr.ac.unist.bus.BuildConfig
import kr.ac.unist.bus.notification.TrackingStateStore

object PushDependencies {
    @Volatile
    private var sharedStateStore: TrackingStateStore? = null

    fun stateStore(context: Context): TrackingStateStore =
        sharedStateStore ?: synchronized(this) {
            sharedStateStore ?: TrackingStateStore(context).also { created ->
                sharedStateStore = created
            }
        }

    fun subscriptionScheduler(context: Context): TrackingSubscriptionScheduler =
        TrackingSubscriptionScheduler(context)

    fun subscriptionSyncer(
        context: Context,
        stateStore: TrackingStateStore = stateStore(context),
    ): TrackingSubscriptionSyncer = TrackingSubscriptionSyncer(
        stateStore = stateStore,
        client = HttpTrackingSubscriptionClient(BuildConfig.UNIST_BUS_API_BASE_URL),
        isEnabled = BuildConfig.FCM_ENABLED,
        pushEnvironment = if (BuildConfig.DEBUG) {
            PushEnvironment.DEVELOPMENT
        } else {
            PushEnvironment.PRODUCTION
        },
    )
}
