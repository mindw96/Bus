package kr.ac.unist.bus.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kr.ac.unist.bus.MainActivity
import kr.ac.unist.bus.R
import kr.ac.unist.bus.model.ArrivalStatus
import kr.ac.unist.bus.model.ArrivalText
import kr.ac.unist.bus.model.StopArrival
import kr.ac.unist.bus.push.ArrivalPushPayload
import kr.ac.unist.bus.push.ArrivalPushType
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class TrackingNotificationController(
    context: Context,
    private val stateStore: TrackingStateStore = TrackingStateStore(context),
) {
    private val appContext = context.applicationContext

    init {
        createChannel()
    }

    val trackedState: PersistedTrackingState?
        get() = stateStore.activeState

    val trackedTripId: String?
        get() = trackedState?.tripId

    fun start(item: StopArrival) {
        requireNotificationPermission()
        stateStore.beginTracking(item)
        post(item)
    }

    fun refresh(item: StopArrival): Boolean {
        if (reconcileTrackingAvailability()) {
            return false
        }
        requireNotificationPermission()
        stateStore.updateFromLocal(item) ?: return false
        post(item)
        return true
    }

    fun stop() {
        stateStore.requestStop()
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
    }

    fun applyRemote(payload: ArrivalPushPayload): Boolean {
        val current = stateStore.activeState ?: return false
        if (current.trackingSessionId != payload.trackingSessionId) {
            return false
        }

        val mustStopLocally = shouldStopLocally(
            state = current,
            notificationsAllowed = notificationsAllowed(),
            now = Instant.now(),
        )
        if (payload.observedAt.isBefore(current.lastObservedAt)) {
            if (mustStopLocally) {
                stop()
            }
            return false
        }
        if (payload.type == ArrivalPushType.TRACKING_END) {
            return finishFromRemote(payload.trackingSessionId)
        }

        val item = payload.item ?: return false
        if (!current.matches(item)) {
            return false
        }
        if (
            item.arrival.status == ArrivalStatus.ARRIVED ||
            item.arrival.status == ArrivalStatus.CANCELLED
        ) {
            return finishFromRemote(payload.trackingSessionId)
        }
        if (mustStopLocally) {
            stop()
            return false
        }

        stateStore.updateFromRemote(
            trackingSessionId = payload.trackingSessionId,
            tripId = item.arrival.tripId,
            estimatedArrivalAt = item.arrival.estimatedArrivalAt,
            observedAt = payload.observedAt,
        ) ?: return false

        if (!notificationsAllowed()) {
            return false
        }
        post(item)
        return true
    }

    fun reconcileTrackingAvailability(
        now: Instant = Instant.now(),
    ): Boolean {
        val current = stateStore.activeState ?: return false
        if (!shouldStopLocally(current, notificationsAllowed(), now)) {
            return false
        }
        stop()
        return true
    }

    private fun finishFromRemote(trackingSessionId: String): Boolean {
        if (!stateStore.finishFromRemote(trackingSessionId)) {
            return false
        }
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun post(item: StopArrival) {
        if (!notificationsAllowed()) {
            return
        }
        NotificationManagerCompat.from(appContext).notify(
            NOTIFICATION_ID,
            buildNotification(item),
        )
    }

    private fun buildNotification(item: StopArrival): Notification {
        val arrival = item.arrival
        val now = Instant.now()
        val phase = trackingNotificationPhase(item, now)
        val remainingMillis = Duration.between(now, arrival.estimatedArrivalAt)
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

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bus_notification)
            .setContentTitle("${arrival.routeName}번 · ${arrival.destination} 방면")
            .setContentText("${ArrivalText.status(item, now)} · ${item.stop.name}")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(appContext.getColor(R.color.unist_blue))
            .setTimeoutAfter(
                Duration.between(now, arrival.estimatedArrivalAt.plus(TRACKING_GRACE_PERIOD))
                    .toMillis()
                    .coerceAtLeast(0),
            )
            .addAction(
                R.drawable.ic_stop_tracking,
                appContext.getString(R.string.stop_tracking),
                stopIntent,
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            applyMetricStyle(
                builder = builder,
                item = item,
                phase = phase,
            )
        } else {
            applyCustomLayout(
                builder = builder,
                item = item,
                phase = phase,
                remainingMillis = remainingMillis,
            )
        }

        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private fun applyMetricStyle(
        builder: NotificationCompat.Builder,
        item: StopArrival,
        phase: TrackingNotificationPhase,
    ) {
        val arrival = item.arrival
        val primaryValue: NotificationCompat.Metric.MetricValue = when (phase) {
            TrackingNotificationPhase.COUNTDOWN ->
                NotificationCompat.Metric.TimeDifference.forTimer(
                    arrival.estimatedArrivalAt,
                    NotificationCompat.Metric.TimeDifference.FORMAT_ADAPTIVE,
                )

            else -> NotificationCompat.Metric.FixedText(primaryStatusText(item, phase))
        }
        val primaryLabel = if (phase == TrackingNotificationPhase.COUNTDOWN) {
            "남은 시간"
        } else {
            "상태"
        }
        val style = NotificationCompat.MetricStyle()
            .addMetric(NotificationCompat.Metric(primaryValue, primaryLabel))
            .setCriticalMetric(0)

        if (
            phase != TrackingNotificationPhase.COMPLETED &&
            phase != TrackingNotificationPhase.CANCELLED
        ) {
            val referenceAt = if (phase == TrackingNotificationPhase.STALE) {
                arrival.observedAt
            } else {
                arrival.estimatedArrivalAt
            }
            val referenceLabel = if (phase == TrackingNotificationPhase.STALE) {
                "마지막 확인"
            } else {
                "${item.countdownKind.eventName} 예정"
            }
            style.addMetric(
                NotificationCompat.Metric(
                    NotificationCompat.Metric.FixedTime(
                        referenceAt.atZone(ZoneId.systemDefault()).toLocalTime(),
                    ),
                    referenceLabel,
                ),
            )
        }

        builder
            .setStyle(style)
            .setSubText(item.stop.name)
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setRequestPromotedOngoing(true)
    }

    private fun applyCustomLayout(
        builder: NotificationCompat.Builder,
        item: StopArrival,
        phase: TrackingNotificationPhase,
        remainingMillis: Long,
    ) {
        val compact = trackingRemoteViews(
            layoutId = R.layout.notification_tracking_compact,
            item = item,
            phase = phase,
            remainingMillis = remainingMillis,
        )
        val expanded = trackingRemoteViews(
            layoutId = R.layout.notification_tracking_expanded,
            item = item,
            phase = phase,
            remainingMillis = remainingMillis,
        )

        builder
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .setCustomHeadsUpContentView(compact)
            .setShowWhen(false)
            .setUsesChronometer(false)
    }

    private fun trackingRemoteViews(
        layoutId: Int,
        item: StopArrival,
        phase: TrackingNotificationPhase,
        remainingMillis: Long,
    ): RemoteViews {
        val arrival = item.arrival
        val eventName = item.countdownKind.eventName

        return RemoteViews(appContext.packageName, layoutId).apply {
            setTextViewText(R.id.notification_route_name, "${arrival.routeName}번")
            setTextViewText(R.id.notification_destination, "${arrival.destination} 방면")
            setTextViewText(R.id.notification_countdown_label, "남은 시간:")
            setTextViewText(R.id.notification_stop_name, item.stop.name)
            setTextViewText(
                R.id.notification_scheduled_time,
                if (phase == TrackingNotificationPhase.STALE) {
                    "마지막 확인 ${scheduledTime(arrival.observedAt)}"
                } else {
                    "$eventName 예정 ${scheduledTime(arrival.estimatedArrivalAt)}"
                },
            )

            if (phase == TrackingNotificationPhase.COUNTDOWN) {
                setViewVisibility(R.id.notification_countdown_label, View.VISIBLE)
                setViewVisibility(R.id.notification_countdown_chronometer, View.VISIBLE)
                setViewVisibility(R.id.notification_primary_status, View.GONE)
                setChronometer(
                    R.id.notification_countdown_chronometer,
                    SystemClock.elapsedRealtime() + remainingMillis,
                    null,
                    true,
                )
                setChronometerCountDown(R.id.notification_countdown_chronometer, true)
            } else {
                setViewVisibility(R.id.notification_countdown_label, View.GONE)
                setViewVisibility(R.id.notification_countdown_chronometer, View.GONE)
                setViewVisibility(R.id.notification_primary_status, View.VISIBLE)
                setTextViewText(
                    R.id.notification_primary_status,
                    primaryStatusText(item, phase),
                )
            }
        }
    }

    private fun primaryStatusText(
        item: StopArrival,
        phase: TrackingNotificationPhase,
    ): String = when (phase) {
        TrackingNotificationPhase.COMPLETED ->
            if (item.countdownKind.eventName == "출발") "출발 완료" else "도착"
        TrackingNotificationPhase.CANCELLED -> "운행 취소"
        TrackingNotificationPhase.STALE -> "데이터 지연"
        TrackingNotificationPhase.IMMINENT -> "곧 ${item.countdownKind.eventName}"
        TrackingNotificationPhase.UNKNOWN -> "정보 확인 중"
        TrackingNotificationPhase.COUNTDOWN -> ""
    }

    private fun requireNotificationPermission() {
        if (!notificationsAllowed()) {
            throw SecurityException("Notification permission is not granted")
        }
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

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
        const val ACTION_STOP_TRACKING = "kr.ac.unist.bus.action.STOP_TRACKING"
        private val TRACKING_GRACE_PERIOD = Duration.ofMinutes(15)

        private val TIME_FORMATTER = DateTimeFormatter
            .ofPattern("a h:mm", Locale.KOREAN)
    }
}

internal fun shouldStopLocally(
    state: PersistedTrackingState,
    notificationsAllowed: Boolean,
    now: Instant,
): Boolean = !notificationsAllowed || now >= state.expiresAt

internal enum class TrackingNotificationPhase {
    COUNTDOWN,
    IMMINENT,
    STALE,
    UNKNOWN,
    COMPLETED,
    CANCELLED,
}

internal fun trackingNotificationPhase(
    item: StopArrival,
    now: Instant,
): TrackingNotificationPhase {
    val arrival = item.arrival
    return when {
        arrival.status == ArrivalStatus.CANCELLED -> TrackingNotificationPhase.CANCELLED
        arrival.status == ArrivalStatus.ARRIVED -> TrackingNotificationPhase.COMPLETED
        now >= arrival.staleAt -> TrackingNotificationPhase.STALE
        arrival.status == ArrivalStatus.ARRIVING || now >= arrival.estimatedArrivalAt ->
            TrackingNotificationPhase.IMMINENT
        arrival.status == ArrivalStatus.UNKNOWN -> TrackingNotificationPhase.UNKNOWN
        else -> TrackingNotificationPhase.COUNTDOWN
    }
}
