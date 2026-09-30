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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps the wheel connection alive with the screen off: foreground service of type
 * `connectedDevice`, which also updates the ride notification, posts silent alerts and shows the
 * optional overlay bubble. Gyro makes no sound and does not vibrate.
 */
class WheelService : LifecycleService() {

    private val container by lazy { (application as GyroApp).container }
    private lateinit var overlay: OverlayController
    private var prefs = Prefs()

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
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
        super.onDestroy()
    }

    private fun syncOverlay() {
        if (prefs.overlayEnabled && overlay.canShow()) overlay.show() else overlay.hide()
    }

    private fun handle(event: RideEvent) {
        when (event) {
            is RideEvent.Alarm -> {
                val rule = event.event.rule
                if (rule.metric == AlarmMetric.BATTERY) postAlert(rule.title, "Заряд ${event.event.value.roundToInt()}%")
            }
            is RideEvent.WheelWarning -> postAlert("Колесо сообщает", event.alert.text)
        }
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
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { notificationManager().notify(ALERT_NOTIFICATION_ID + (text.hashCode() and 0xFF), n) }
    }

    private fun createChannels() {
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(RIDE_CHANNEL, getString(R.string.notification_channel_ride), NotificationManager.IMPORTANCE_LOW),
        )
        // Alerts pop up on screen but stay silent: no sound, no vibration.
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, getString(R.string.notification_channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        // Channel settings are fixed once created; drop the old channel that used the default sound and vibration.
        nm.deleteNotificationChannel(LEGACY_ALERT_CHANNEL)
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val ACTION_CONNECT = "app.gyro.action.CONNECT"
        private const val ACTION_STOP = "app.gyro.action.STOP"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_FAMILY = "family"
        private const val RIDE_CHANNEL = "ride"
        private const val ALERT_CHANNEL = "alerts_silent"
        private const val LEGACY_ALERT_CHANNEL = "alerts"
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
