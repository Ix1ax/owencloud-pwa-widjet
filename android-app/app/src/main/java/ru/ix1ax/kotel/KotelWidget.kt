package ru.ix1ax.kotel

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
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

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context)
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
            DashboardMonitor.owenChanged(context)
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
            DashboardMonitor.owenChanged(context)
        }

        /** Перерисовать все виджеты из сохранённых значений. */
        fun render(context: Context, busy: String? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, KotelWidget::class.java))
            if (ids.isEmpty()) return
            if (Build.VERSION.SDK_INT >= 31) {
                // Android 12+: система сама выбирает раскладку под размер виджета
                val views = RemoteViews(mapOf(
                    SizeF(110f, 110f) to build(context, busy, Kind.SMALL),
                    SizeF(220f, 50f) to build(context, busy, Kind.STRIP),
                    SizeF(220f, 115f) to build(context, busy, Kind.MEDIUM),
                ))
                manager.updateAppWidget(ids, views)
            } else {
                for (id in ids) manager.updateAppWidget(id, build(context, busy, kindFor(manager, id)))
            }
        }

        /** Для Android 11 и старше — выбираем раскладку по текущему размеру виджета. */
        private fun kindFor(manager: AppWidgetManager, id: Int): Kind {
            val o = manager.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
            return when {
                w < 220 -> Kind.SMALL
                h < 115 -> Kind.STRIP
                else -> Kind.MEDIUM
            }
        }

        fun requestUpdateAll(context: Context) {
            context.sendBroadcast(Intent(context, KotelWidget::class.java).setAction(ACTION_REFRESH))
        }

        enum class Kind(val layout: Int) {
            SMALL(R.layout.widget_small), STRIP(R.layout.widget_strip), MEDIUM(R.layout.widget_medium)
        }

        private fun build(context: Context, busy: String?, kind: Kind): RemoteViews {
            val prefs = Prefs(context)
            val s = prefs.state
            val v = RemoteViews(context.packageName, kind.layout)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val openApp = PendingIntent.getActivity(context, 11, Intent(context, MainActivity::class.java), flags)
            if (kind == Kind.MEDIUM) v.setTextViewText(R.id.w_title, prefs.deviceName)

            if (!prefs.isConfigured) {
                v.setTextViewText(R.id.w_temp, "—")
                v.setTextViewText(R.id.w_sub, "Войдите в приложении")
                v.setOnClickPendingIntent(R.id.w_root, openApp)
                return v
            }

            val offline = prefs.offline
            val alarm = s.alarm
            v.setTextViewText(R.id.w_temp, formatTemp(s.temp))
            themeText(context, v, R.id.w_temp, if (offline) R.color.w_offline else R.color.w_text)

            // Лампы
            v.setImageViewResource(R.id.w_dot_work, if (s.working) R.drawable.dot_green else R.drawable.dot_off)
            v.setImageViewResource(R.id.w_dot_alarm, if (alarm) R.drawable.dot_red else R.drawable.dot_off)
            when (kind) {
                Kind.MEDIUM -> {
                    v.setTextViewText(R.id.w_work_text, if (s.working) "Работает" else "Стоит")
                    themeText(context, v, R.id.w_work_text, if (s.working) R.color.w_text else R.color.w_sub)
                    v.setTextViewText(R.id.w_alarm_text, if (alarm) "Авария" else "Нет аварий")
                    themeText(context, v, R.id.w_alarm_text, if (alarm) R.color.red else R.color.w_sub)
                }
                Kind.STRIP -> {
                    themeText(context, v, R.id.w_work_text, if (s.working) R.color.w_text else R.color.w_sub)
                    themeText(context, v, R.id.w_alarm_text, if (alarm) R.color.red else R.color.w_sub)
                }
                Kind.SMALL -> Unit
            }

            // Подпись под температурой
            val time = if (prefs.lastTime > 0) timeFmt(prefs.lastTime) else ""
            val target = s[if (P.isOn(s[P.SELECT])) P.SP2 else P.SP1]
            val sub = busy ?: when {
                offline -> offlineText(prefs)
                prefs.lastStatus != null -> prefs.lastStatus!!
                kind == Kind.SMALL -> if (alarm) "Авария" else if (s.working) "Работает · $time" else "Стоит · $time"
                kind == Kind.MEDIUM && target != null -> "цель ${formatTemp(target)} · $time"
                else -> time
            }
            v.setTextViewText(R.id.w_sub, sub)
            themeText(context, v, R.id.w_sub, when {
                busy != null -> R.color.w_sub
                offline -> R.color.orange
                prefs.lastStatus != null -> R.color.w_sub
                kind == Kind.SMALL && alarm -> R.color.red
                else -> R.color.w_sub
            })

            // Кнопка сброса: синяя — Reset = 1, светлая — Reset = 0
            val on = P.isOn(s.reset)
            val value = if (s.reset == null) "—" else if (on) "1" else "0"
            v.setTextViewText(R.id.w_reset, when (kind) {
                Kind.SMALL -> "Сброс $value"
                Kind.STRIP -> value
                Kind.MEDIUM -> "Сброс: $value"
            })
            v.setInt(R.id.w_reset, "setBackgroundResource", if (on) R.drawable.w_rs_on else R.drawable.w_rs_off)
            v.setTextColor(R.id.w_reset, if (on) Color.WHITE else context.getColor(R.color.blue))

            v.setOnClickPendingIntent(R.id.w_info, PendingIntent.getBroadcast(
                context, 1, Intent(context, KotelWidget::class.java).setAction(ACTION_REFRESH), flags
            ))
            v.setOnClickPendingIntent(R.id.w_icon, openApp)
            v.setOnClickPendingIntent(R.id.w_lamps, openApp)
            v.setOnClickPendingIntent(R.id.w_reset, writeIntent(context, prefs, P.RESET, if (on) "0" else "1", "Сброс"))
            return v
        }

        /**
         * Цвет текста из ресурсов. На Android 12+ цвет берётся в момент отрисовки,
         * поэтому сам переключается при смене светлой/тёмной темы телефона.
         */
        private fun themeText(context: Context, v: RemoteViews, id: Int, colorRes: Int) {
            if (Build.VERSION.SDK_INT >= 31) v.setColorStateList(id, "setTextColor", colorRes)
            else v.setTextColor(id, context.getColor(colorRes))
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
