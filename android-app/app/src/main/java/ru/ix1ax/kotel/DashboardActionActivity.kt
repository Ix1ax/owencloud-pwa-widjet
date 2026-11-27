package ru.ix1ax.kotel

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlin.concurrent.thread

class DashboardActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        val target = intent.getIntExtra(DashboardWidget.EXTRA_TARGET, -1)
        val id = DashboardWidget.widgetId(intent)
        val index = intent.getIntExtra(DashboardWidget.EXTRA_TILE_INDEX, -1)
        val tile = DashboardStore(this).config(id)?.tiles?.getOrNull(index)
        if (!DashboardWidget.isDashboard(this, id) || tile == null ||
            tile.kind != intent.getStringExtra(DashboardWidget.EXTRA_TILE_KIND) ||
            tile.deviceId != intent.getStringExtra(DashboardWidget.EXTRA_DEVICE)) {
            finish()
            return
        }
        val data = DashboardData(this)
        val owen = tile.owen
        when {
            target in 0..1 && owen?.parameter != null && data.owen.isConfigured -> askOwen(data.owen, owen, target)
            target in 0..1 && tile.kind == DashboardTile.YANDEX_POWER -> askYandex(data, tile, target)
            else -> open(if (tile.yandex) YandexHomeActivity::class.java else MainActivity::class.java)
        }
    }

    private fun askOwen(prefs: Prefs, tile: OwenTile, target: Int) {
        val parameter = tile.parameter ?: return finish()
        if (parameter !in prefs.ids) return finishWith("Параметр недоступен у прибора")
        val login = prefs.login
        val device = prefs.deviceId
        val choices = if (tile == OwenTile.RESET) listOf("0", "1") else listOf("Выключить", "Включить")
        ask(tile.title, prefs.deviceName, choices[target], target) { selected ->
            val current = Prefs(applicationContext)
            if (current.login != login || current.deviceId != device) return@ask "Аккаунт OwenCloud изменился"
            val applied = Repo.write(current, parameter, selected.toString())
            KotelWidget.render(applicationContext)
            DashboardMonitor.owenChanged(applicationContext)
            if (applied) "Команда OwenCloud записана" else "Команда OwenCloud в очереди"
        }
    }

    private fun askYandex(data: DashboardData, tile: DashboardTile, target: Int) {
        val account = data.account
        if (account == null || !account.session.canControl) return open(YandexHomeActivity::class.java)
        val device = data.snapshot?.devices?.firstOrNull { it.id == tile.deviceId && it.supportsPower }
            ?: return finishWith("Устройство недоступно. Обновите виджет")
        ask(device.name ?: "Устройство Яндекса", device.location, if (target == 1) "Включить" else "Выключить", target) { selected ->
            val context = applicationContext
            if (DashboardData.yandexAccount(context)?.owner != account.owner) return@ask "Аккаунт Яндекса изменился"
            YandexHomeApi.setPower(account.session.accessToken, tile.deviceId, selected == 1)
            if (DashboardMonitor.refreshYandex(context, account)) "Команда Яндекса выполнена"
            else "Команда выполнена. Обновите показания виджета"
        }
    }

    private fun ask(title: String, location: String, action: String, target: Int, write: (Int) -> String) {
        if (Prefs(this).confirmWrite) {
            confirmCommand(title, location, action, onCancel = { finish() }) { submit(write, target) }
        } else submit(write, target)
    }

    private fun submit(write: (Int) -> String, selected: Int) {
        val dialog = if (Prefs(this).confirmWrite) AlertDialog.Builder(this)
            .setMessage("Отправляем команду…").setCancelable(false).show() else null
        if (dialog == null) finish()
        thread {
            val message = runCatching { write(selected) }.getOrElse {
                when (it) {
                    is ApiException -> it.message ?: "OwenCloud не подтвердил команду"
                    is YandexRequestFailure -> when {
                        it.status == 403 -> "Яндекс отказал в доступе к управлению"
                        it.deviceError == "DEVICE_UNREACHABLE" -> "Устройство недоступно для Яндекса"
                        else -> "Яндекс не подтвердил команду. Обновите показания"
                    }
                    else -> "Не удалось получить результат команды. Обновите показания"
                }
            }
            runOnUiThread {
                if (!isDestroyed) dialog?.dismiss()
                if (isFinishing) Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                else if (!isDestroyed) finishWith(message)
            }
        }
    }

    private fun open(activity: Class<*>) {
        startActivity(Intent(this, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
