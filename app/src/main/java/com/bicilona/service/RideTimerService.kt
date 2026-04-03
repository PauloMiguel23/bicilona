package com.bicilona.service

import android.app.*
import android.content.Context
import android.content.Intent

import android.os.*
import androidx.core.app.NotificationCompat
import com.bicilona.R
import com.bicilona.ui.MainActivity

class RideTimerService : Service() {

    companion object {
        const val CHANNEL_ID = "ride_timer_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.bicilona.START_TIMER"
        const val ACTION_STOP = "com.bicilona.STOP_TIMER"

        const val EXTRA_TIME_LIMIT_MINUTES = "time_limit_minutes"
        const val EXTRA_WARNING_MINUTES = "warning_minutes"
        const val EXTRA_REDIRECT_MINUTES = "redirect_minutes"

        var isRunning = false
            private set

        // Direct callbacks — service and activity share the same process
        var onTick: ((secsLeft: Int) -> Unit)? = null
        var onWarning: (() -> Unit)? = null
        /** Return the LatLng (lat, lon) of the nearest station with docks, or null */
        var findRedirectStation: (() -> Pair<Double, Double>?)? = null
        var onRedirect: (() -> Unit)? = null
        var onFinished: (() -> Unit)? = null

        fun clearCallbacks() {
            onTick = null
            onWarning = null
            findRedirectStation = null
            onRedirect = null
            onFinished = null
        }
    }

    private var countDownTimer: CountDownTimer? = null
    private var hasWarned = false
    private var hasRedirected = false
    private var vibrator: Vibrator? = null
    private var warningSeconds = 0
    private var redirectSeconds = 0
    private var totalSeconds = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val timeLimitMin = intent.getIntExtra(EXTRA_TIME_LIMIT_MINUTES, 30)
                val warningMin = intent.getIntExtra(EXTRA_WARNING_MINUTES, 5)
                val redirectMin = intent.getIntExtra(EXTRA_REDIRECT_MINUTES, 1)
                startTimer(timeLimitMin, warningMin, redirectMin)
            }
            ACTION_STOP -> {
                stopTimer()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTimer(timeLimitMin: Int, warningMin: Int, redirectMin: Int) {
        countDownTimer?.cancel()
        hasWarned = false
        hasRedirected = false
        isRunning = true

        totalSeconds = timeLimitMin * 60
        warningSeconds = warningMin * 60
        redirectSeconds = redirectMin * 60

        val notification = buildNotification(totalSeconds, totalSeconds)
        startForeground(NOTIFICATION_ID, notification)

        countDownTimer = object : CountDownTimer(totalSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secsLeft = (millisUntilFinished / 1000).toInt()
                updateNotification(secsLeft, totalSeconds)

                // Update activity UI via direct callback
                onTick?.invoke(secsLeft)

                // Warning vibration
                if (!hasWarned && secsLeft <= warningSeconds) {
                    hasWarned = true
                    vibrateWarning()
                    onWarning?.invoke()
                }

                // Auto-redirect: launch navigation from the foreground service
                // (allowed to start activities from background)
                // redirectSeconds < 0 means redirect is disabled
                if (!hasRedirected && redirectSeconds > 0 && secsLeft <= redirectSeconds) {
                    hasRedirected = true
                    vibrateRedirect()
                    launchRedirectNavigation()
                    onRedirect?.invoke()
                }
            }

            override fun onFinish() {
                vibrateRedirect()
                updateNotificationFinished()
                onFinished?.invoke()
                isRunning = false
            }
        }.start()
    }

    /**
     * Find the nearest station with docks and launch navigation via a full-screen
     * intent notification. This works even when the phone is locked/screen off,
     * because Android treats full-screen intents like alarm/call screens —
     * waking the device and showing the activity over the lock screen.
     */
    private fun launchRedirectNavigation() {
        val coords = findRedirectStation?.invoke() ?: return

        // Create a high-priority channel for the redirect alert
        val redirectChannel = NotificationChannel(
            "ride_redirect_channel",
            "Ride Redirect",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Navigates to nearest station when time is running out"
            enableVibration(true)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(redirectChannel)

        // Intent that wakes screen, shows over lock screen, and launches Google Maps
        val redirectIntent = Intent(this, com.bicilona.ui.RedirectActivity::class.java).apply {
            putExtra(com.bicilona.ui.RedirectActivity.EXTRA_LAT, coords.first)
            putExtra(com.bicilona.ui.RedirectActivity.EXTRA_LNG, coords.second)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        val fullScreenPi = PendingIntent.getActivity(
            this, 100, redirectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Full-screen intent notification — Android will either:
        // - Launch the activity directly (screen off/locked) — like an alarm
        // - Show a heads-up notification (screen on) — tap to navigate
        val notification = NotificationCompat.Builder(this, "ride_redirect_channel")
            .setSmallIcon(R.drawable.ic_bike_foreground)
            .setContentTitle("🚨 Redirecting to nearest station")
            .setContentText("Time is running out — navigating you to a dock")
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun stopTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        isRunning = false
        super.onDestroy()
    }

    // ── Notifications ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ride Timer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows remaining free ride time"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(secsLeft: Int, totalSecs: Int): Notification {
        val mins = secsLeft / 60
        val secs = secsLeft % 60
        val timeStr = String.format("%02d:%02d", mins, secs)

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RideTimerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bike_foreground)
            .setContentTitle("🚲 Ride Timer: $timeStr")
            .setContentText("Free ride time remaining")
            .setContentIntent(contentIntent)
            .addAction(0, "Stop Timer", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(totalSecs, totalSecs - secsLeft, false)
            .build()
    }

    private fun updateNotification(secsLeft: Int, totalSecs: Int) {
        val notification = buildNotification(secsLeft, totalSecs)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationFinished() {
        val alertChannel = NotificationChannel(
            "ride_timer_alert",
            "Ride Timer Alert",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alert when free ride time expires"
            enableVibration(true)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(alertChannel)

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "ride_timer_alert")
            .setSmallIcon(R.drawable.ic_bike_foreground)
            .setContentTitle("🚨 Free ride time expired!")
            .setContentText("You're now being charged extra")
            .setContentIntent(contentIntent)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    // ── Vibration ──────────────────────────────────────────────────

    private fun vibrateWarning() {
        val pattern = longArrayOf(0, 300, 200, 300)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
    }

    private fun vibrateRedirect() {
        val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
    }
}
