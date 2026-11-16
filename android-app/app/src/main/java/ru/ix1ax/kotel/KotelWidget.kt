package ru.ix1ax.kotel

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.SystemClock
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Виджет: температура, лампы «Работа» / «Авария» и одна кнопка сброса 0 ⇄ 1.
 * Нажатие на температуру — обновить.
 */
class KotelWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context)
        background(context) { refresh(context, busy = true) }
        scheduleTick(context)
    }

    override fun onEnabled(context: Context) = scheduleTick(context)

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(tickIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TICK -> {
                // Тихое обновление раз в минуту, без надписи «обновляю…»
                background(context) { refresh(context, busy = false) }
                scheduleTick(context)
            }
            // После перезагрузки и обновления приложения таймер сбрасывается — запускаем заново
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                scheduleTick(context)
                background(context) { refresh(context, busy = false) }
            }
            ACTION_REFRESH -> background(context) { refresh(context, busy = true) }
            ACTION_WRITE -> {
                val code = intent.getStringExtra(EXTRA_CODE) ?: return
                val value = intent.getStringExtra(EXTRA_VALUE) ?: return
                val label = intent.getStringExtra(EXTRA_LABEL) ?: code
                background(context) { write(context, code, value, label) }
            }
        }
    }

    private fun background(context: Context, block: () -> Unit) {
        val pending = goAsync()
        thread {
            try { block() } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_REFRESH = "ru.ix1ax.kotel.REFRESH"
        const val ACTION_TICK = "ru.ix1ax.kotel.TICK"
        private const val TICK_MS = 60_000L

        private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 20,
            Intent(context, KotelWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /**
         * Следующее обновление через минуту. ELAPSED_REALTIME (без WAKEUP) не будит телефон:
         * пока экран выключен и телефон спит — обновлений нет, батарея не тратится;
         * как только телефон проснулся — сразу срабатывает и дальше идёт раз в минуту.
         */
        fun scheduleTick(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            if (manager.getAppWidgetIds(ComponentName(context, KotelWidget::class.java)).isEmpty()) return
            context.getSystemService(AlarmManager::class.java).set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + TICK_MS,
                tickIntent(context)
            )
        }
        const val ACTION_WRITE = "ru.ix1ax.kotel.WRITE"
        const val EXTRA_CODE = "code"
        const val EXTRA_VALUE = "value"
        const val EXTRA_LABEL = "label"

        fun refresh(context: Context, busy: Boolean) {
            val prefs = Prefs(context)
            if (!prefs.loggedIn) { render(context); return }
            if (busy) render(context, "обновляю…")
            Repo.refresh(prefs)
            render(context)
        }

        /** Запись из виджета или из окошка подтверждения. Блокирующий вызов. */
        fun write(context: Context, code: String, value: String, label: String) {
            val prefs = Prefs(context)
            try {
                render(context, "записываю…")
                val applied = Repo.write(prefs, code, value)
                prefs.lastStatus = if (applied) null else "команда в очереди"
            } catch (e: Exception) {
                prefs.lastStatus = "не записалось: ${e.message}"
            }
            render(context)
        }

        /** Перерисовать все виджеты из сохранённых значений. */
        fun render(context: Context, busy: String? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, KotelWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, build(context, busy))
        }

        fun requestUpdateAll(context: Context) {
            context.sendBroadcast(Intent(context, KotelWidget::class.java).setAction(ACTION_REFRESH))
        }

        private fun build(context: Context, busy: String?): RemoteViews {
            val prefs = Prefs(context)
            val s = prefs.state
            val v = RemoteViews(context.packageName, R.layout.widget_kotel)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

            if (!prefs.isConfigured) {
                v.setTextViewText(R.id.w_temp, "—")
                v.setTextViewText(R.id.w_meta, "Откройте «Owen Настройки» и войдите")
                val open = PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java), flags)
                v.setOnClickPendingIntent(R.id.w_root, open)
                return v
            }

            val offline = prefs.offline
            v.setTextViewText(R.id.w_temp, formatTemp(s.temp))
            v.setTextColor(R.id.w_temp, if (offline) Color.parseColor("#80FFFFFF") else Color.WHITE)
            v.setImageViewResource(R.id.w_lamp_work, if (s.working) R.drawable.lamp_green else R.drawable.lamp_off)
            v.setImageViewResource(R.id.w_lamp_alarm, if (s.alarm) R.drawable.lamp_red else R.drawable.lamp_off)

            val time = if (prefs.lastTime > 0) " · " + timeFmt(prefs.lastTime) else ""
            val meta = busy
                ?: if (offline) offlineText(prefs) else prefs.lastStatus ?: (prefs.deviceName + time)
            v.setTextViewText(R.id.w_meta, meta)
            v.setTextColor(R.id.w_meta, if (offline && busy == null) Color.parseColor("#FFC46B") else Color.parseColor("#A6FFFFFF"))

            // Кнопка сброса: залитая — Reset = 1, контурная — Reset = 0
            val on = P.isOn(s.reset)
            v.setTextViewText(R.id.w_reset_val, if (s.reset == null) "—" else if (on) "1" else "0")
            v.setInt(R.id.w_reset, "setBackgroundResource", if (on) R.drawable.reset_on else R.drawable.reset_off)
            val fg = if (on) Color.parseColor("#111111") else Color.WHITE
            v.setTextColor(R.id.w_reset_val, fg)
            v.setTextColor(R.id.w_reset_lbl, if (on) Color.parseColor("#555555") else Color.parseColor("#B3FFFFFF"))

            v.setOnClickPendingIntent(R.id.w_info, PendingIntent.getBroadcast(
                context, 1, Intent(context, KotelWidget::class.java).setAction(ACTION_REFRESH), flags
            ))
            v.setOnClickPendingIntent(R.id.w_lamps, PendingIntent.getActivity(
                context, 11, Intent(context, MainActivity::class.java), flags
            ))
            v.setOnClickPendingIntent(R.id.w_reset, writeIntent(context, prefs, P.RESET, if (on) "0" else "1", "Сброс"))
            return v
        }

        private fun writeIntent(context: Context, prefs: Prefs, code: String, value: String, label: String): PendingIntent {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return if (prefs.confirmWrite) {
                val i = Intent(context, ConfirmActivity::class.java)
                    .putExtra(EXTRA_CODE, code).putExtra(EXTRA_VALUE, value).putExtra(EXTRA_LABEL, label)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                PendingIntent.getActivity(context, 100, i, flags)
            } else {
                val i = Intent(context, KotelWidget::class.java).setAction(ACTION_WRITE)
                    .putExtra(EXTRA_CODE, code).putExtra(EXTRA_VALUE, value).putExtra(EXTRA_LABEL, label)
                PendingIntent.getBroadcast(context, 2, i, flags)
            }
        }

        fun formatTemp(raw: String?): String {
            val n = P.num(raw) ?: return raw ?: "—"
            return String.format(Locale.US, "%.1f°", n)
        }

        fun formatNum(raw: String?): String {
            val n = P.num(raw) ?: return raw ?: "—"
            return String.format(Locale.US, "%.1f", n)
        }

        /** «14:20», а если не сегодня — «15.11 14:20». */
        fun timeFmt(unix: Long): String {
            val date = Date(unix * 1000)
            val day = SimpleDateFormat("yyyyMMdd", Locale.US)
            val today = day.format(Date()) == day.format(date)
            return SimpleDateFormat(if (today) "HH:mm" else "dd.MM HH:mm", Locale.getDefault()).format(date)
        }

        fun offlineText(prefs: Prefs): String =
            if (prefs.lastTime > 0) "нет связи с " + timeFmt(prefs.lastTime) else "нет связи с прибором"
    }
}
