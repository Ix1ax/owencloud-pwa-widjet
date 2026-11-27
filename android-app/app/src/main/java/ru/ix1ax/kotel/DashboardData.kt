package ru.ix1ax.kotel

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

internal data class DashboardYandexAccount(val session: YandexSession, val owner: String)
internal data class DashboardCard(val title: String, val value: String, val alert: Boolean = false)

internal class DashboardData(private val context: Context) {
    val owen = Prefs(context)
    private val store = DashboardStore(context)
    val account = yandexAccount(context)
    val snapshot = account?.let { store.yandex(it.owner) }
    private val oldYandex = snapshot != null &&
        (System.currentTimeMillis() - snapshot.fetchedAt > 30 * 60_000L || account?.let { store.yandexFailed(it.owner) } == true)

    fun card(tile: DashboardTile): DashboardCard {
        tile.owen?.let { return owenCard(it) }
        val device = snapshot?.devices?.firstOrNull { it.id == tile.deviceId }
        val isPower = tile.kind == DashboardTile.YANDEX_POWER
        val name = device?.name ?: if (isPower) "Управление Яндексом" else "Устройство Яндекса"
        val selectedMetric = tile.yandexMetric
        val metric = if (isPower) YandexMetric.POWER else selectedMetric
        val title = when {
            device != null && isPower -> "$name · Вкл./выкл."
            selectedMetric != null -> "$name · ${context.getString(selectedMetric.label)}"
            else -> name
        }
        if (account == null) return DashboardCard(title, "Войдите в Яндекс")
        if (device == null) return DashboardCard(title, if (snapshot == null) "Ожидаем обновления" else "Устройство недоступно")
        val readings = device.readings.filter { metric == null || it.metric == metric }
        val value = readings.joinToString(" · ") {
            val reading = it.displayValue(context)
            if (metric == null && readings.size > 1) "${context.getString(it.metric.label)} $reading" else reading
        }.ifBlank { "Нет показаний" }
        return DashboardCard(
            title,
            when {
                isPower && !account.session.canControl -> "$value · нужен доступ"
                oldYandex -> "$value · данные устарели"
                else -> value
            },
            readings.any { it.metric == YandexMetric.WATER_LEAK && it.value == "leak" }
        )
    }

    private fun owenCard(tile: OwenTile): DashboardCard {
        if (!owen.loggedIn) return DashboardCard(tile.title, "Войдите в OwenCloud")
        val state = owen.state
        val value = when (tile) {
            OwenTile.TEMPERATURE -> KotelWidget.formatTemp(state.temp)
            OwenTile.SETPOINT -> KotelWidget.formatTemp(state[if (P.isOn(state[P.SELECT])) P.SP2 else P.SP1])
            OwenTile.BURNER -> state[P.WORKING]?.let { if (P.isOn(it)) "Работает" else "Стоит" }
            OwenTile.ALARM -> if (P.ERRORS.keys.any { state[it] != null }) {
                state.activeErrors.joinToString(", ").ifEmpty { "Нет аварий" }
            } else null
            OwenTile.MODE -> state[P.MODE]?.let { if (P.isOn(it)) "Ручной" else "Авто" }
            OwenTile.RESET -> state[P.RESET]?.let { if (P.isOn(it)) "1" else "0" }
            OwenTile.START -> state[P.START]?.let { if (P.isOn(it)) "Включён" else "Выключен" }
        } ?: "Нет показаний"
        return DashboardCard(
            tile.title,
            if (owen.offline || owen.lastStatus != null) "$value · нет свежих данных" else value,
            tile == OwenTile.ALARM && state.alarm
        )
    }

    companion object {
        fun yandexAccount(context: Context): DashboardYandexAccount? {
            val store = YandexHomeStore(context)
            if (store.clientId != context.getString(R.string.yandex_oauth_client_id)) return null
            val session = store.session?.takeIf { it.expiresAt > System.currentTimeMillis() } ?: return null
            return DashboardYandexAccount(session, fingerprint(session.accessToken))
        }

        fun fingerprint(value: String): String = android.util.Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
            android.util.Base64.NO_WRAP
        )
    }
}

internal fun YandexReading.displayValue(context: Context): String {
    val data = value ?: return context.getString(R.string.yandex_no_value)
    return when (metric) {
        YandexMetric.WATER_LEAK -> context.getString(if (data == "leak") R.string.yandex_leak else R.string.yandex_dry)
        YandexMetric.POWER -> context.getString(if (data == true) R.string.yandex_on else R.string.yandex_off)
        else -> {
            val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(data)
            when (unit) {
                "unit.temperature.celsius" -> context.getString(R.string.yandex_celsius, number)
                "unit.temperature.kelvin" -> context.getString(R.string.yandex_kelvin, number)
                "unit.percent" -> context.getString(R.string.yandex_percent, number)
                else -> number
            }
        }
    }
}

internal val YandexMetric.label: Int get() = when (this) {
    YandexMetric.WATER_LEAK -> R.string.yandex_water
    YandexMetric.TEMPERATURE -> R.string.yandex_temperature
    YandexMetric.HUMIDITY -> R.string.yandex_humidity
    YandexMetric.BATTERY -> R.string.yandex_battery
    YandexMetric.POWER -> R.string.yandex_power
}

internal object DashboardMonitor {
    const val PERIODIC = 6201
    private const val REFRESH = 6202
    private const val CHANNEL = "smart_home_alerts"
    private val yandexFetchLock = Any()
    private val yandexCacheLock = Any()

    fun alertsRequested(context: Context): Boolean = DashboardStore(context).prefs.getBoolean("alerts", false)

    fun setAlerts(context: Context, enabled: Boolean) {
        DashboardStore(context).prefs.edit().putBoolean("alerts", enabled).apply()
        if (!enabled) DashboardFastMonitor.stop(context)
        if (!enabled) context.getSystemService(NotificationManager::class.java).apply {
            cancel("owen", 1)
            cancel("yandex", 1)
        }
        schedule(context)
        if (enabled) requestRefresh(context)
    }

    fun canNotify(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun alertManager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CHANNEL, "Аварии и протечки", NotificationManager.IMPORTANCE_HIGH))
        }

    fun testNotification(context: Context) {
        if (!canNotify(context)) return
        alertManager(context).notify("test", 1, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_alert).setContentTitle("Проверка уведомлений")
            .setContentText("Уведомления OwenCloud и Яндекса доступны.").setAutoCancel(true).build())
    }

    fun refreshOwen(context: Context) {
        val prefs = Prefs(context)
        if (prefs.loggedIn) Repo.refresh(prefs)
        if (!Thread.currentThread().isInterrupted) {
            owenChanged(context)
            KotelWidget.render(context)
        }
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (DashboardWidget.ids(context).isEmpty() && !alertsRequested(context)) {
            scheduler.cancel(PERIODIC)
            return
        }
        if (scheduler.getPendingJob(PERIODIC) == null) {
            scheduler.schedule(job(context, PERIODIC).setPeriodic(15 * 60_000L).setPersisted(true).build())
        }
    }

    fun requestRefresh(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(REFRESH) != null) return
        if (Build.VERSION.SDK_INT >= 31 &&
            scheduler.schedule(job(context, REFRESH).setExpedited(true).build()) == JobScheduler.RESULT_SUCCESS) return
        scheduler.schedule(job(context, REFRESH).build())
    }

    private fun job(context: Context, id: Int): JobInfo.Builder =
        JobInfo.Builder(id, ComponentName(context, DashboardUpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)

    fun refreshYandex(context: Context, expected: DashboardYandexAccount? = null): Boolean = synchronized(yandexFetchLock) {
        val account = DashboardData.yandexAccount(context) ?: return@synchronized false
        if (expected != null && expected.owner != account.owner) return@synchronized false
        val devices = runCatching { YandexHomeApi.devices(account.session.accessToken) }.getOrNull()
        if (devices == null) {
            if (DashboardData.yandexAccount(context)?.owner == account.owner) DashboardStore(context).markYandexFailed(account.owner)
            DashboardWidget.notifyDataChanged(context)
            false
        } else {
            acceptYandex(context, account.session, devices)
            true
        }
    }

    fun acceptYandex(context: Context, session: YandexSession, devices: List<YandexDevice>) {
        synchronized(yandexCacheLock) {
            val account = DashboardData.yandexAccount(context) ?: return
            if (account.session.accessToken != session.accessToken) return
            DashboardStore(context).saveYandex(account.owner, devices)
            // Повторная проверка не даёт сохранить снимок от аккаунта, из которого вышли во время запроса.
            if (DashboardData.yandexAccount(context)?.owner != account.owner) return
            val leaks = devices.mapNotNull { device ->
                val id = device.id ?: return@mapNotNull null
                val reading = device.readings.firstOrNull { it.metric == YandexMetric.WATER_LEAK } ?: return@mapNotNull null
                val active = (reading.value as? String)?.let { it == "leak" }
                id to Pair(active, listOfNotNull(device.name, device.location.takeIf { it.isNotEmpty() }).joinToString(" · "))
            }.toMap()
            notifyNew(context, "yandex", account.owner, "Обнаружена протечка", leaks, YandexHomeActivity::class.java)
        }
        DashboardWidget.notifyDataChanged(context)
    }

    fun clearYandex(context: Context) {
        synchronized(yandexCacheLock) { DashboardStore(context).clearYandex() }
        context.getSystemService(NotificationManager::class.java).cancel("yandex", 1)
        DashboardWidget.notifyDataChanged(context)
    }

    fun clearOwen(context: Context) {
        DashboardStore(context).prefs.edit().remove("alert.owen.owner").remove("alert.owen.active").apply()
        context.getSystemService(NotificationManager::class.java).cancel("owen", 1)
        DashboardWidget.notifyDataChanged(context)
    }

    fun owenChanged(context: Context) {
        if (!alertsRequested(context) && DashboardWidget.ids(context).isEmpty()) return
        val prefs = Prefs(context)
        val login = prefs.login
        if (login != null && prefs.isConfigured && prefs.lastStatus == null && !prefs.offline) {
            val state = prefs.state
            notifyNew(context, "owen", DashboardData.fingerprint("$login:${prefs.deviceId}"),
                "Авария OwenCloud", P.ERRORS.mapValues { (code, label) ->
                    Pair(state[code]?.let(P::isOn), "${prefs.deviceName} · $label")
                }, MainActivity::class.java)
        }
        DashboardWidget.notifyDataChanged(context)
    }

    @Synchronized
    private fun notifyNew(
        context: Context, source: String, owner: String, title: String,
        events: Map<String, Pair<Boolean?, String>>, activity: Class<*>
    ) {
        if (!alertsRequested(context) || !canNotify(context)) return
        val prefs = DashboardStore(context).prefs
        val ownerKey = "alert.$source.owner"
        val activeKey = "alert.$source.active"
        val previous = if (prefs.getString(ownerKey, null) == owner) prefs.getStringSet(activeKey, null).orEmpty() else emptySet()
        val active = events.filter { (id, item) -> item.first ?: (id in previous) }.keys
        val fresh = active - previous
        if (fresh.isNotEmpty()) {
            val manager = alertManager(context)
            val message = fresh.mapNotNull { events[it]?.second?.ifBlank { "Датчик протечки" } }.joinToString("\n")
            val open = PendingIntent.getActivity(context, if (source == "owen") 1 else 2,
                Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            manager.notify(source, 1, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_alert).setContentTitle(title).setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message)).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM).setContentIntent(open).build())
        }
        prefs.edit().putString(ownerKey, owner).putStringSet(activeKey, active).apply()
    }
}

class DashboardUpdateJob : JobService() {
    private val workers = ConcurrentHashMap<Int, Thread>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId == DashboardMonitor.PERIODIC && DashboardFastMonitor.running) return false
        val worker = thread(start = false) {
            try {
                val context = applicationContext
                val ids = DashboardWidget.ids(context)
                val tiles = ids.flatMap { DashboardStore(context).config(it)?.tiles.orEmpty() }
                val alerts = DashboardMonitor.alertsRequested(context)
                if (tiles.any { it.owen != null } || alerts) DashboardMonitor.refreshOwen(context)
                if (!Thread.currentThread().isInterrupted && (tiles.any { it.yandex } || alerts)) {
                    DashboardMonitor.refreshYandex(context)
                }
            } finally {
                if (workers.remove(params.jobId, Thread.currentThread())) jobFinished(params, false)
            }
        }
        workers.put(params.jobId, worker)?.interrupt()
        worker.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        workers.remove(params.jobId)?.interrupt()
        return false
    }
}
