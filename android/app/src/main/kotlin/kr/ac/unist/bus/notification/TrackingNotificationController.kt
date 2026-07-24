package kr.ac.unist.bus.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kr.ac.unist.bus.MainActivity
import kr.ac.unist.bus.R
import kr.ac.unist.bus.model.StopArrival
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class TrackingNotificationController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        createChannel()
    }

    val trackedTripId: String?
        get() = preferences.getString(KEY_TRIP_ID, null)

    fun start(item: StopArrival) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Notification permission is not granted")
        }

        preferences.edit {
            putString(KEY_TRIP_ID, item.arrival.tripId)
        }

        NotificationManagerCompat.from(appContext).notify(
            NOTIFICATION_ID,
            buildNotification(item),
        )
    }

    fun stop() {
        preferences.edit {
            remove(KEY_TRIP_ID)
        }
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(item: StopArrival): Notification {
        val arrival = item.arrival
        val eventName = item.countdownKind.eventName
        val remainingMillis = Duration.between(Instant.now(), arrival.estimatedArrivalAt)
            .toMillis()
            .coerceAtLeast(0)

        val openAppIntent = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getBroadcast(
            appContext,
            1,
            Intent(appContext, StopTrackingReceiver::class.java).apply {
                action = ACTION_STOP_TRACKING
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle("${arrival.routeName}번 · ${arrival.destination} 방면")
            .setContentText("$eventName\uAE4C\uC9C0 \uB0A8\uC740 \uC2DC\uAC04 · ${item.stop.name}")
            .setSubText("$eventName 예정 ${scheduledTime(arrival.estimatedArrivalAt)}")
            .setContentIntent(openAppIntent)
            .setWhen(arrival.estimatedArrivalAt.toEpochMilli())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(appContext.getColor(R.color.unist_blue))
            .setRequestPromotedOngoing(true)
            .setTimeoutAfter(remainingMillis + Duration.ofMinutes(15).toMillis())
            .addAction(
                R.drawable.ic_stop_tracking,
                appContext.getString(R.string.stop_tracking),
                stopIntent,
            )
            .build()
    }

    private fun createChannel() {
        val notificationManager = appContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = appContext.getString(R.string.tracking_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun scheduledTime(instant: Instant): String =
        TIME_FORMATTER.withZone(ZoneId.systemDefault()).format(instant)

    companion object {
        private const val CHANNEL_ID = "bus_arrival_tracking"
        private const val NOTIFICATION_ID = 40233
        private const val PREFERENCES_NAME = "tracking_state"
        private const val KEY_TRIP_ID = "tracked_trip_id"
        const val ACTION_STOP_TRACKING = "kr.ac.unist.bus.action.STOP_TRACKING"

        private val TIME_FORMATTER = DateTimeFormatter
            .ofPattern("a h:mm", Locale.KOREAN)
    }
}
