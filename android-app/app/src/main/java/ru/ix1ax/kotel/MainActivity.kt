package ru.ix1ax.kotel

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import kotlin.concurrent.thread

/** Вход в OwenCloud и кнопка «Добавить виджет». Нужен один раз. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        findViewById<Button>(R.id.login_btn).setOnClickListener { doLogin() }
        findViewById<Button>(R.id.add_widget).setOnClickListener { addWidget() }
        findViewById<Button>(R.id.refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.logout).setOnClickListener { logout() }
        findViewById<Switch>(R.id.confirm_switch).apply {
            isChecked = prefs.confirmWrite
            setOnCheckedChangeListener { _, checked ->
                prefs.confirmWrite = checked
                KotelWidget.render(this@MainActivity)
            }
        }
        showState()
    }

    private fun showState() {
        val ready = prefs.isConfigured
        findViewById<View>(R.id.login_panel).visibility = if (ready) View.GONE else View.VISIBLE
        findViewById<View>(R.id.ready_panel).visibility = if (ready) View.VISIBLE else View.GONE
        if (ready) {
            findViewById<TextView>(R.id.device).text = prefs.deviceName
            findViewById<TextView>(R.id.account).text = prefs.login
            renderValues()
            refresh()
        }
    }

    private fun renderValues() {
        findViewById<TextView>(R.id.temp).text = KotelWidget.formatTemp(prefs.lastTemp)
        val time = if (prefs.lastTime > 0) " · обновлено " + KotelWidget.timeFmt(prefs.lastTime) else ""
        findViewById<TextView>(R.id.meta).text = prefs.lastStatus ?: "Reset: ${prefs.lastReset ?: "—"}$time"
    }

    private fun refresh() {
        findViewById<TextView>(R.id.meta).text = "Обновляю…"
        thread {
            KotelWidget.refresh(this, busy = null)
            ui.post { renderValues() }
        }
    }

    private fun doLogin() {
        val email = findViewById<EditText>(R.id.email).text.toString().trim()
        val password = findViewById<EditText>(R.id.password).text.toString()
        val msg = findViewById<TextView>(R.id.login_msg)
        val btn = findViewById<Button>(R.id.login_btn)
        if (email.isEmpty() || password.isEmpty()) {
            msg.text = "Введите email и пароль"
            return
        }
        btn.isEnabled = false
        msg.text = "Вхожу…"
        thread {
            val result = runCatching {
                val api = OwenApi(prefs)
                val token = api.login(email, password)
                prefs.login = email
                prefs.password = password
                prefs.token = token
                api.autoSetup()
            }
            ui.post {
                btn.isEnabled = true
                result.fold(
                    onSuccess = { found ->
                        if (found) {
                            msg.text = ""
                            showState()
                            KotelWidget.render(this)
                        } else {
                            msg.text = "Вход выполнен, но не нашёл прибор с параметрами P512 и P527"
                        }
                    },
                    onFailure = { msg.text = it.message ?: "Ошибка входа" }
                )
            }
        }
    }

    private fun addWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        val provider = ComponentName(this, KotelWidget::class.java)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(provider, null, null)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Добавьте виджет вручную")
                .setMessage("Долгое нажатие на пустом месте рабочего стола → Виджеты → «Котёл».")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun logout() {
        AlertDialog.Builder(this)
            .setTitle("Выйти?")
            .setMessage("Логин, пароль и настройки будут стёрты с этого телефона.")
            .setPositiveButton("Выйти") { _, _ ->
                prefs.clear()
                KotelWidget.render(this)
                findViewById<EditText>(R.id.password).setText("")
                showState()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
