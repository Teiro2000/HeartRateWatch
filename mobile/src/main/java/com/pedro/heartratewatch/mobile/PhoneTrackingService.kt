package com.pedro.heartratewatch.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.AlertType
import com.pedro.heartratewatch.shared.LocationDistanceTracker
import com.pedro.heartratewatch.shared.RouteCodec
import com.pedro.heartratewatch.shared.RoutePoint
import com.pedro.heartratewatch.shared.TrainingSettings
import com.pedro.heartratewatch.shared.defaultSettingsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tracks a cardio session with the phone's own GPS, for when the watch isn't being used: pace (or
 * speed on a bike), distance, best splits, time stopped on a bike, and the same pace and distance
 * alerts as the watch, played here through [AlertPlayer]. There is no heart rate, so heart rate
 * alerts don't apply. It uses the same per-activity settings as the watch, and the finished
 * session lands in History like any other. Started manually from the Cardio menu ("Track with
 * phone"); the watch isn't involved at all.
 */
class PhoneTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var alertPlayer: AlertPlayer
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var historyRepository: RunHistoryRepository

    private val routePoints = mutableListOf<RoutePoint>()
    private var tracker: PhoneSessionTracker? = null
    private var location: LocationDistanceTracker? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var settings: TrainingSettings = TrainingSettings()
    private var settingsJob: Job? = null

    // Pace alert state, mirroring the watch's per-metric AlertChannel.
    private var onBreak = false
    private var pushHarderAlerting = false
    private var lastPushHarderMillis = 0L
    private var halfwaySent = false
    private var targetSent = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishSession()
                return START_NOT_STICKY
            }
            else -> {
                if (tracker == null) {
                    val type = intent?.getStringExtra(EXTRA_ACTIVITY_TYPE)
                        ?.let { runCatching { ActivityType.valueOf(it) }.getOrNull() }
                        ?: ActivityType.RUN
                    startSession(type)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startSession(type: ActivityType) {
        alertPlayer = AlertPlayer(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        historyRepository = RunHistoryRepository(applicationContext)
        settings = defaultSettingsFor(type)
        settingsJob = scope.launch {
            settingsRepository.settingsFlow(type).collect { settings = it }
        }

        val notification = buildNotification(type)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulseGuard:PhoneTracking")
            .apply { setReferenceCounted(false); acquire(MAX_WAKE_LOCK_MS) }

        val start = System.currentTimeMillis()
        val session = PhoneSessionTracker(type, start)
        tracker = session
        LiveRunState.setPhoneSession(
            LiveRunState.Snapshot(isActive = true, activityType = type, phoneSession = true)
        )

        // A stationary bike has no GPS: it is just timed, and its distance is typed in afterwards in History.
        if (!type.usesGps) return
        try {
            location = LocationDistanceTracker(
                applicationContext,
                maxSpeedMetersPerSecond = if (type == ActivityType.BIKE) LocationDistanceTracker.MAX_BIKE_SPEED_METERS_PER_SECOND
                else LocationDistanceTracker.MAX_FOOT_SPEED_METERS_PER_SECOND,
                onPoint = { lat, lon -> routePoints += RoutePoint(lat, lon, System.currentTimeMillis()) }
            ) { meters -> onDistance(session, meters) }.also { it.start() }
        } catch (e: SecurityException) {
            // Location permission missing: nothing to track.
            tracker = null
            LiveRunState.clearPhoneSession()
            stopSelf()
        }
    }

    private fun onDistance(session: PhoneSessionTracker, meters: Float) {
        val now = System.currentTimeMillis()
        val pace = session.onDistance(now, meters)
        LiveRunState.setPhoneSession(
            LiveRunState.Snapshot(
                isActive = true,
                activityType = session.type,
                distanceMeters = session.distanceMeters,
                currentPaceSecPerKm = session.currentPaceSecPerKm,
                phoneSession = true
            )
        )
        if (pace != null) handlePace(pace, now - session.startedAtMillis)
        checkDistanceProgress(session.distanceMeters)
    }

    private fun handlePace(paceSecPerKm: Int, elapsedMillis: Long) {
        if (!settings.paceAlertsEnabled) return
        val inWarmup = elapsedMillis < settings.warmupSeconds * 1000L

        if (pushHarderAlerting && paceSecPerKm <= settings.slowestPaceSecPerKm) {
            pushHarderAlerting = false
            alertPlayer.stop()
        }
        when {
            paceSecPerKm < settings.fastestPaceSecPerKm -> {
                if (onBreak) return
                // Too fast: ease up. Alert once, then stay quiet for the break length.
                onBreak = true
                alertPlayer.play(AlertType.PACE_TOO_FAST)
                scope.launch {
                    delay(settings.breakTimerSeconds * 1000L)
                    onBreak = false
                    alertPlayer.stop()
                }
            }
            paceSecPerKm > settings.slowestPaceSecPerKm && !inWarmup -> {
                if (onBreak) return
                val now = System.currentTimeMillis()
                if (now - lastPushHarderMillis < PUSH_HARDER_INTERVAL_MS) return
                lastPushHarderMillis = now
                pushHarderAlerting = true
                alertPlayer.play(AlertType.PACE_TOO_SLOW)
            }
        }
    }

    private fun checkDistanceProgress(meters: Float) {
        val target = settings.distanceTargetMeters ?: return
        if (!halfwaySent && meters >= target / 2f) {
            halfwaySent = true
            alertPlayer.play(AlertType.HALFWAY)
        }
        if (!targetSent && meters >= target) {
            targetSent = true
            alertPlayer.play(AlertType.TARGET_REACHED)
        }
    }

    /** Stops tracking, files the session in History (if it got anywhere), and shuts the service down. */
    private fun finishSession() {
        val session = tracker
        tracker = null
        location?.stop()
        location = null
        alertPlayer.stop()
        if (session != null) {
            val summary = session.summary(System.currentTimeMillis())
            // A start-and-immediately-stop isn't worth a history entry.
            if (summary.distanceMeters > 0f || summary.durationSeconds >= MIN_SAVED_SECONDS) {
                val route = if (routePoints.size >= 2) RouteCodec.encode(RouteCodec.downsample(routePoints)) else null
                CoroutineScope(Dispatchers.IO).launch { SessionIngest.ingest(applicationContext, summary, route) }
            }
        }
        LiveRunState.clearPhoneSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        location?.stop()
        settingsJob?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (tracker != null) LiveRunState.clearPhoneSession()
        super.onDestroy()
    }

    private fun buildNotification(type: ActivityType): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phone tracking", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PhoneTrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_streak)
            .setContentTitle("Tracking ${type.displayName().lowercase()} on your phone")
            .setContentText("Tap to open PulseGuard")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.pedro.heartratewatch.mobile.PHONE_TRACKING_STOP"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        private const val CHANNEL_ID = "phone_tracking"
        private const val NOTIFICATION_ID = 5001
        private const val PUSH_HARDER_INTERVAL_MS = 20_000L
        private const val MIN_SAVED_SECONDS = 30
        private const val MAX_WAKE_LOCK_MS = 6 * 60 * 60 * 1_000L

        fun start(context: Context, type: ActivityType) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneTrackingService::class.java).putExtra(EXTRA_ACTIVITY_TYPE, type.name)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PhoneTrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}
