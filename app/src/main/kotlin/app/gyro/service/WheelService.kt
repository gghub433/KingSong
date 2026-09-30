package app.gyro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.gyro.GyroApp
import app.gyro.MainActivity
import app.gyro.R
import app.gyro.ble.ConnectionState
import app.gyro.ble.WheelTarget
import app.gyro.data.Prefs
import app.gyro.data.RideEvent
import app.gyro.data.RideSnapshot
import app.gyro.protocol.AlarmMetric
import app.gyro.protocol.ProtocolFamily
import app.gyro.protocol.TiltbackPredictor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps the wheel connection alive with the screen off: foreground service of type
 * `connectedDevice`, which also speaks alarms, vibrates, updates the ride notification and shows
 * the optional overlay bubble.
 */
class WheelService : LifecycleService() {

    private val container by lazy { (application as GyroApp).container }
    private lateinit var voice: VoiceAnnouncer
    private lateinit var haptics: Haptics
    private lateinit var overlay: OverlayController
    private var prefs = Prefs()

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        voice = VoiceAnnouncer(this)
        haptics = Haptics(this)
        overlay = OverlayController(this)
        createChannels()

        lifecycleScope.launch { container.prefs.prefs.collect { prefs = it; syncOverlay() } }
        lifecycleScope.launch { container.rideMonitor.events.collect { handle(it) } }
        lifecycleScope.launch {
            container.rideMonitor.snapshot.sample(1_000).collect { s ->
                notificationManager().notify(RIDE_NOTIFICATION_ID, rideNotification(s))
                overlay.update(s)
            }
        }
        lifecycleScope.launch { periodicSummary() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                container.connection.disconnect()
                overlay.hide()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                if (!enterForeground()) return START_NOT_STICKY
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_NOT_STICKY
                val family = intent.getStringExtra(EXTRA_FAMILY)?.let { f -> ProtocolFamily.entries.firstOrNull { it.name == f } }
                container.connection.connect(WheelTarget(address, intent.getStringExtra(EXTRA_NAME), family))
            }
            else -> if (!enterForeground()) return START_NOT_STICKY
        }
        // If Android kills the process mid-ride, the CONNECT intent is redelivered and the link restored.
        return START_REDELIVER_INTENT
    }

    private fun enterForeground(): Boolean = runCatching {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, RIDE_NOTIFICATION_ID, rideNotification(container.rideMonitor.snapshot.value), type)
    }.onFailure { stopSelf() }.isSuccess

    override fun onDestroy() {
        overlay.hide()
        voice.shutdown()
        super.onDestroy()
    }

    private fun syncOverlay() {
        if (prefs.overlayEnabled && overlay.canShow()) overlay.show() else overlay.hide()
    }

    private fun handle(event: RideEvent) {
        when (event) {
            is RideEvent.Alarm -> {
                val rule = event.event.rule
                if (rule.voice && prefs.voiceEnabled) voice.speak(event.event.spoken, urgent = rule.metric != AlarmMetric.BATTERY)
                if (rule.vibrate && prefs.vibrationEnabled) haptics.alarm()
                if (rule.metric == AlarmMetric.BATTERY) postAlert(rule.title, "Заряд ${event.event.value.roundToInt()}%")
            }
            is RideEvent.Tiltback -> {
                val a = event.assessment
                val critical = a.level == TiltbackPredictor.Level.CRITICAL
                if (prefs.vibrationEnabled) if (critical) haptics.critical() else haptics.alarm()
                if (prefs.voiceEnabled) voice.speak(tiltbackPhrase(a), urgent = true)
            }
            is RideEvent.WheelWarning -> {
                if (prefs.voiceEnabled) voice.speak(event.alert.text, urgent = false)
                if (prefs.vibrationEnabled) haptics.alarm()
                postAlert("Колесо сообщает", event.alert.text)
            }
            is RideEvent.Connected -> if (prefs.voiceEnabled) voice.speak("Колесо подключено")
            RideEvent.ConnectionLost -> {
                if (prefs.voiceEnabled) voice.speak("Связь с колесом потеряна", urgent = true)
                if (prefs.vibrationEnabled) haptics.critical()
            }
        }
    }

    private fun tiltbackPhrase(a: TiltbackPredictor.Assessment): String = when (a.reason) {
        TiltbackPredictor.Reason.SPEED -> "Сбавь скорость, близко к пределу"
        TiltbackPredictor.Reason.PWM_TREND -> "Нагрузка растёт, сбавь"
        else -> "Нагрузка ${a.pwmPercent?.roundToInt() ?: ""} процентов, сбавь"
    }

    private suspend fun periodicSummary() {
        while (lifecycleScope.isActive) {
            val minutes = prefs.voiceIntervalMin
            if (!prefs.voiceEnabled || minutes <= 0) {
                delay(15_000)
                continue
            }
            delay(minutes * 60_000L)
            val s = container.rideMonitor.snapshot.value
            if (prefs.voiceEnabled && s.live) voice.speak(summary(s))
        }
    }

    /** "Скорость 32. Заряд 64 процента. Запас 25 километров." */
    private fun summary(s: RideSnapshot): String {
        val t = s.wheel?.telemetry ?: return ""
        val parts = mutableListOf("Скорость ${t.absSpeedKmh.roundToInt()}")
        t.batteryPercent?.roundToInt()?.let { parts += "Заряд $it ${VoiceAnnouncer.plural(it, "процент", "процента", "процентов")}" }
        s.range?.km?.roundToInt()?.let { parts += "Запас $it ${VoiceAnnouncer.plural(it, "километр", "километра", "километров")}" }
        t.maxTemperatureC?.roundToInt()?.let { parts += "Температура $it" }
        return parts.joinToString(". ")
    }

    private fun rideNotification(s: RideSnapshot): Notification {
        val t = s.wheel?.telemetry
        val title = s.wheel?.info?.displayName ?: s.connection.target?.name ?: "Gyro"
        val text = when (val c = s.connection) {
            is ConnectionState.Connected -> if (t != null) {
                listOfNotNull(
                    "${t.absSpeedKmh.roundToInt()} км/ч",
                    t.batteryPercent?.let { "${it.roundToInt()}%" },
                    t.maxTemperatureC?.let { "${it.roundToInt()}°C" },
                    s.range?.let { "≈${it.km.roundToInt()} км" },
                ).joinToString(" · ")
            } else {
                "Подключено"
            }
            is ConnectionState.Connecting -> "Подключение…"
            is ConnectionState.Identifying -> "Определение модели…"
            is ConnectionState.Reconnecting -> "Переподключение (${c.attempt}): ${c.reason}"
            is ConnectionState.Failed -> c.reason
            ConnectionState.Idle -> "Не подключено"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WheelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, RIDE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_gyro)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Отключить", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun postAlert(title: String, text: String) {
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_gyro)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { notificationManager().notify(ALERT_NOTIFICATION_ID + (text.hashCode() and 0xFF), n) }
    }

    private fun createChannels() {
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(RIDE_CHANNEL, getString(R.string.notification_channel_ride), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, getString(R.string.notification_channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val ACTION_CONNECT = "app.gyro.action.CONNECT"
        private const val ACTION_STOP = "app.gyro.action.STOP"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_FAMILY = "family"
        private const val RIDE_CHANNEL = "ride"
        private const val ALERT_CHANNEL = "alerts"
        private const val RIDE_NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 100

        fun connect(context: Context, target: WheelTarget) {
            val intent = Intent(context, WheelService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_ADDRESS, target.address)
                .putExtra(EXTRA_NAME, target.name)
                .putExtra(EXTRA_FAMILY, target.family?.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WheelService::class.java).setAction(ACTION_STOP))
        }
    }
}
