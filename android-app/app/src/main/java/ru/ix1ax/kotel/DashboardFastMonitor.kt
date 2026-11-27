package ru.ix1ax.kotel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DashboardFastMonitor : Service() {
    private val workers = Executors.newScheduledThreadPool(2)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Быстрый мониторинг", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, DashboardBuilderActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, DashboardFastMonitor::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(6210, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_refresh).setContentTitle("Быстрый мониторинг")
            .setContentText("Проверка OwenCloud и Яндекса примерно раз в минуту")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build())

        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:fast-monitor")
        lock.acquire()
        wakeLock = lock
        running = true
        DashboardMonitor.schedule(this)
        val context = applicationContext
        poll { DashboardMonitor.refreshOwen(context) }
        poll { DashboardMonitor.refreshYandex(context) }
        return START_NOT_STICKY
    }

    private fun poll(refresh: () -> Unit) {
        workers.scheduleAtFixedRate({
            if (DashboardMonitor.alertsRequested(this) && DashboardMonitor.canNotify(this)) {
                runCatching(refresh).onFailure { android.util.Log.w("DashboardFastMonitor", "Refresh failed", it) }
            } else stopSelf()
        }, 0, 60, TimeUnit.SECONDS)
    }

    override fun onDestroy() {
        workers.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        running = false
        DashboardMonitor.schedule(this)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "smart_home_monitor"
        private const val STOP = "ru.ix1ax.kotel.STOP_FAST_MONITOR"
        @Volatile var running = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DashboardFastMonitor::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DashboardFastMonitor::class.java))
        }
    }
}
