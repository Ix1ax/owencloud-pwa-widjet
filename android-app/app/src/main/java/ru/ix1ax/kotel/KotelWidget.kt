package ru.ix1ax.kotel

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Виджет: температура, текущее значение Reset и две кнопки RESET 1 / RESET 0.
 * Нажатие на температуру — обновить. Кнопки пишут Reset прямо из виджета
 * (с окошком подтверждения, если оно включено в приложении).
 */
class KotelWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context)
        refreshAsync(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> refreshAsync(context)
            ACTION_WRITE -> {
                val value = intent.getStringExtra(EXTRA_VALUE) ?: return
                writeAsync(context, value)
            }
        }
    }

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        thread {
            try {
                refresh(context, busy = "Обновляю…")
            } finally {
                pending.finish()
            }
        }
    }

    private fun writeAsync(context: Context, value: String) {
        val pending = goAsync()
        thread {
            val prefs = Prefs(context)
            try {
                render(context, busy = "Отправляю Reset = $value…")
                OwenApi(prefs).write(prefs.resetId, value)
                // Запись идёт через облако асинхронно — пару раз перечитываем значение
                var applied = false
                for (delay in longArrayOf(2500, 3500)) {
                    Thread.sleep(delay)
                    refresh(context, busy = null)
                    if (prefs.lastReset == value) { applied = true; break }
                }
                prefs.lastStatus = if (applied) "Reset = $value ✓" else "Команда отправлена, прибор применит её"
                render(context)
            } catch (e: Exception) {
                prefs.lastStatus = "Не записалось: ${e.message}"
                render(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "ru.ix1ax.kotel.REFRESH"
        const val ACTION_WRITE = "ru.ix1ax.kotel.WRITE"
        const val EXTRA_VALUE = "value"

        /** Загружает свежие значения и перерисовывает все виджеты. Блокирующий вызов. */
        fun refresh(context: Context, busy: String?) {
            val prefs = Prefs(context)
            if (!prefs.isConfigured) { render(context); return }
            if (busy != null) render(context, busy)
            try {
                val data = OwenApi(prefs).lastData(listOf(prefs.tempId, prefs.resetId))
                data[prefs.tempId]?.let {
                    prefs.lastTemp = it.value
                    prefs.lastTime = it.time
                }
                data[prefs.resetId]?.let { prefs.lastReset = it.value }
                prefs.lastStatus = null
            } catch (e: Exception) {
                prefs.lastStatus = e.message ?: "Ошибка"
            }
            render(context)
        }

        /** Перерисовать все виджеты из сохранённых значений. */
        fun render(context: Context, busy: String? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, KotelWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(context, busy)
            manager.updateAppWidget(ids, views)
        }

        fun requestUpdateAll(context: Context) {
            context.sendBroadcast(Intent(context, KotelWidget::class.java).setAction(ACTION_REFRESH))
        }

        private fun build(context: Context, busy: String?): RemoteViews {
            val prefs = Prefs(context)
            val v = RemoteViews(context.packageName, R.layout.widget_kotel)

            v.setTextViewText(R.id.w_title, prefs.deviceName)

            if (!prefs.isConfigured) {
                v.setTextViewText(R.id.w_temp, "—")
                v.setTextViewText(R.id.w_meta, "Откройте приложение «Котёл» и войдите")
                val open = PendingIntent.getActivity(
                    context, 10, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                v.setOnClickPendingIntent(R.id.w_root, open)
                return v
            }

            v.setTextViewText(R.id.w_temp, formatTemp(prefs.lastTemp))
            val reset = prefs.lastReset ?: "—"
            val time = if (prefs.lastTime > 0) " · " + timeFmt(prefs.lastTime) else ""
            v.setTextViewText(R.id.w_meta, busy ?: prefs.lastStatus ?: "Reset: $reset$time")

            v.setInt(R.id.w_btn1, "setBackgroundResource",
                if (prefs.lastReset == "1") R.drawable.btn_reset1_active else R.drawable.btn_reset)
            v.setInt(R.id.w_btn0, "setBackgroundResource",
                if (prefs.lastReset == "0") R.drawable.btn_reset0_active else R.drawable.btn_reset)

            // Нажатие на температуру — обновить
            v.setOnClickPendingIntent(R.id.w_info, PendingIntent.getBroadcast(
                context, 1,
                Intent(context, KotelWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))
            v.setOnClickPendingIntent(R.id.w_btn1, writeIntent(context, prefs, "1", 2))
            v.setOnClickPendingIntent(R.id.w_btn0, writeIntent(context, prefs, "0", 3))
            return v
        }

        private fun writeIntent(context: Context, prefs: Prefs, value: String, code: Int): PendingIntent {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return if (prefs.confirmWrite) {
                val i = Intent(context, ConfirmActivity::class.java)
                    .putExtra(EXTRA_VALUE, value)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                PendingIntent.getActivity(context, 100 + code, i, flags)
            } else {
                val i = Intent(context, KotelWidget::class.java)
                    .setAction(ACTION_WRITE)
                    .putExtra(EXTRA_VALUE, value)
                PendingIntent.getBroadcast(context, code, i, flags)
            }
        }

        fun formatTemp(raw: String?): String {
            val n = raw?.replace(',', '.')?.toDoubleOrNull() ?: return raw ?: "—"
            return String.format(Locale.US, "%.1f°", n)
        }

        fun timeFmt(unix: Long): String =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(unix * 1000))
    }
}
