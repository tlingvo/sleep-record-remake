package sleep.record

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.Calendar
import java.util.Locale

internal fun notificationsAllowed(c: Context) =
    Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

class SleepService : Service() {
    companion object {
        private const val FOREGROUND = "foreground_service_channel"
        private const val RATE = "penalty_rate_channel"
        private const val BEDTIME = "sleep_time_channel"
        private const val STOP = "sleep.record.STOP_SLEEP_NOTIFICATION"
        private var active: SleepService? = null
        fun start(context: Context) {
            runCatching { context.startForegroundServiceCompat(Intent(context, SleepService::class.java)) }
        }
        fun update() { active?.checkNow() }
        internal fun schedule(context: Context, config: Config) {
            val manager = context.getSystemService(AlarmManager::class.java)
            val pending = PendingIntent.getBroadcast(context, 1003, Intent(context, SleepAlarm::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val time = clock(now()).apply {
                set(Calendar.HOUR_OF_DAY, config.start)
                set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now()) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
                if (runCatching { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending) }.isSuccess) return
            }
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        }
        fun syncFromActivity(context: Context) {
            val config = Store.get(context).config()
            if (config.asleep(localHour())) {
                if (active == null) start(context) else update()
            } else if (active != null) update() else schedule(context, config)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var manager: NotificationManager
    private var started = false
    private val reminded = mutableSetOf<String>()
    private val tick = Runnable { checkNow() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        active = this
        manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            listOf(Triple(FOREGROUND, "睡眠监测", NotificationManager.IMPORTANCE_LOW),
                Triple(RATE, "扣分率", NotificationManager.IMPORTANCE_LOW),
                Triple(BEDTIME, "睡眠时间提醒", NotificationManager.IMPORTANCE_DEFAULT)).forEach { (id, label, importance) ->
                manager.createNotificationChannel(NotificationChannel(id, label, importance).apply {
                    if (id != BEDTIME) { setSound(null, null); enableVibration(false); setShowBadge(false) }
                })
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            val stop = PendingIntent.getService(this, 2, Intent(this, SleepService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = builder(FOREGROUND, "睡时记录", "睡眠时段通知运行中", true)
                .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
            if (runCatching { startForeground(9999, notification) }.isFailure) {
                stopSelf()
                return START_NOT_STICKY
            }
            started = true
        }
        checkNow()
        return START_STICKY
    }
    private fun checkNow() {
        if (!started) return
        val config = Store.get(this).config()
        val ms = now()
        val hour = localHour(ms)
        handler.removeCallbacks(tick)
        if (!hasUsageAccess(this) || !config.asleep(hour)) {
            manager.cancel(1001)
            if (hasUsageAccess(this)) schedule(this, config)
            stopSelf()
            return
        }
        val allowed = notificationsAllowed(this) && manager.areNotificationsEnabled()
        val date = config.date(ms)
        if (allowed && config.flag("showSleepTimeNotification") && reminded.add(date)) {
            manager.notify(1002, builder(BEDTIME, "睡觉时间到了！", String.format(Locale.ROOT, "设定睡眠时间 %02d:00，准备放下手机休息吧。", config.start), false).build())
        } else if (!config.flag("showSleepTimeNotification")) manager.cancel(1002)
        if (allowed && config.flag("showPenaltyNotification")) {
            manager.notify(1001, builder(RATE, "当前扣分率", String.format(Locale.ROOT, "每分钟扣 %.2f 分", config.rate(hour)), true).build())
        } else manager.cancel(1001)
        handler.postDelayed(tick, config.number("notificationCheckIntervalSec") * 1000L)
    }
    @Suppress("DEPRECATION")
    private fun builder(channel: String, title: String, body: String, ongoing: Boolean): Notification.Builder {
        val entry = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_moon_notification).setContentTitle(title).setContentText(body).setContentIntent(entry)
            .setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(ongoing)
            .setPriority(if (ongoing) Notification.PRIORITY_LOW else Notification.PRIORITY_DEFAULT)
    }
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        manager.cancel(1001)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (active === this) active = null
        super.onDestroy()
    }
}

private fun Context.startForegroundServiceCompat(intent: Intent) {
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
}
class SleepAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (hasUsageAccess(context)) SleepService.start(context)
    }
}

