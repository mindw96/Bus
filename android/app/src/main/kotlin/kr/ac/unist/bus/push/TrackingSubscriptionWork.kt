package kr.ac.unist.bus.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kr.ac.unist.bus.BuildConfig

class TrackingSubscriptionSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        if (!BuildConfig.FCM_ENABLED) {
            return Result.success()
        }
        return if (PushDependencies.subscriptionSyncer(applicationContext).syncPending()) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}

class TrackingSubscriptionScheduler(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun enqueue() {
        if (!BuildConfig.FCM_ENABLED) {
            return
        }

        val request = OneTimeWorkRequest.Builder(TrackingSubscriptionSyncWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                MINIMUM_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "tracking-subscription-sync"
        const val WORK_TAG = "tracking-subscription"
        const val MINIMUM_BACKOFF_SECONDS = 10L
    }
}
