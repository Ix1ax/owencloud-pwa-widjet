package ru.ix1ax.kotel

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread

/** Главный экран: температура, лампы, причины аварии и управление прибором. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private var writing = false
    private var bindingSwitch = false

    // Уставки, которые пользователь меняет кнопками − / +, но ещё не записал
    private val pending = HashMap<String, Double>()
    private val pendingCommit = HashMap<String, Runnable>()

    private val autoRefresh = object : Runnable {
        override fun run() {
            if (prefs.isConfigured && !writing && pending.isEmpty()) refresh(quiet = true)
            ui.postDelayed(this, 15000)
        }
    }

    private fun <T : View> v(id: Int): T = findViewById(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        v<Button>(R.id.login_btn).setOnClickListener { doLogin() }
        val openYandex = View.OnClickListener {
            startActivity(Intent(this, YandexHomeActivity::class.java))
        }
        v<View>(R.id.yandex_entry_login).setOnClickListener(openYandex)
        v<View>(R.id.yandex_entry_ready).setOnClickListener(openYandex)
        val openBuilder = View.OnClickListener { startActivity(Intent(this, DashboardBuilderActivity::class.java)) }
        v<View>(R.id.dashboard_entry_login).setOnClickListener(openBuilder)
        v<View>(R.id.dashboard_entry_ready).setOnClickListener(openBuilder)
        v<View>(R.id.menu).setOnClickListener { showMenu(it) }
        v<TextView>(R.id.temp).setOnClickListener { refresh(quiet = false) }

        v<TextView>(R.id.mode_auto).setOnClickListener { ask(P.MODE, "0", "Режим", "Авто") }
        v<TextView>(R.id.mode_manual).setOnClickListener { ask(P.MODE, "1", "Режим", "Ручной") }
        v<TextView>(R.id.sel_1).setOnClickListener { ask(P.SELECT, "0", "Уставка", "1") }
        v<TextView>(R.id.sel_2).setOnClickListener { ask(P.SELECT, "1", "Уставка", "2") }

        setupStepper(P.SP1, "Уставка 1", R.id.sp1_minus, R.id.sp1_val, R.id.sp1_plus)
        setupStepper(P.SP2, "Уставка 2", R.id.sp2_minus, R.id.sp2_val, R.id.sp2_plus)

        v<Switch>(R.id.start_switch).setOnCheckedChangeListener { sw, checked ->
            if (bindingSwitch) return@setOnCheckedChangeListener
            sw.isChecked = !checked // вернём, пока не подтвердят и не запишется
            ask(P.START, if (checked) "1" else "0", "Удалённый старт", if (checked) "Вкл" else "Выкл")
        }
        v<TextView>(R.id.reset_btn).setOnClickListener {
            val target = if (P.isOn(prefs.state.reset)) "0" else "1"
            ask(P.RESET, target, "Сброс", target)
        }

        showState()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(autoRefresh)
        if (prefs.isConfigured) { render(); refresh(quiet = true) }
        ui.postDelayed(autoRefresh, 15000)
        KotelWidget.scheduleTick(this) // на случай, если таймер виджета остановился
        DashboardMonitor.schedule(this)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(autoRefresh)
    }

    // ---------- Экраны ----------

    private fun showState() {
        val ready = prefs.isConfigured
        v<View>(R.id.login_panel).visibility = if (ready) View.GONE else View.VISIBLE
        v<View>(R.id.ready_panel).visibility = if (ready) View.VISIBLE else View.GONE
        if (ready) render()
    }

    private fun render() {
        val s = prefs.state
        val offline = prefs.offline
        v<TextView>(R.id.device).text = prefs.deviceName

        // Строка статуса под заголовком
        val online = !offline && prefs.lastTime > 0
        v<ImageView>(R.id.status_dot).setImageResource(
            when { offline -> R.drawable.circle_orange; online -> R.drawable.circle_green; else -> R.drawable.circle_gray })
        v<TextView>(R.id.status_line).apply {
            text = when {
                offline -> KotelWidget.offlineText(prefs).replaceFirstChar { it.uppercase() }
                prefs.lastTime > 0 -> "Онлайн · обновлено " + KotelWidget.timeFmt(prefs.lastTime)
                else -> "Загрузка…"
            }
            setTextColor(color(if (offline) R.color.orange else R.color.secondary))
        }

        // Температура и цель (активная уставка)
        v<TextView>(R.id.temp).apply {
            text = KotelWidget.formatTemp(s.temp)
            setTextColor(color(if (offline) R.color.secondary else R.color.text))
        }
        val sel2 = P.isOn(s[P.SELECT])
        val target = s[if (sel2) P.SP2 else P.SP1]
        v<TextView>(R.id.target).text = if (target != null) "цель " + KotelWidget.formatTemp(target) else ""

        // Плитка «Горелка»
        v<ImageView>(R.id.burner_icon).apply {
            setBackgroundResource(if (s.working) R.drawable.circle_green else R.drawable.circle_gray)
            setImageResource(if (s.working) R.drawable.ic_bolt else R.drawable.ic_bolt_off)
        }
        v<TextView>(R.id.burner_text).apply {
            text = if (!s.hasWorking) "—" else if (s.working) "Работает" else "Стоит"
            setTextColor(color(if (s.working) R.color.text else R.color.secondary))
        }

        // Плитка «Аварии» — красная, если есть хоть одна
        val errors = s.activeErrors
        val alarm = errors.isNotEmpty()
        v<LinearLayout>(R.id.tile_alarm).setBackgroundResource(if (alarm) R.drawable.card_red else R.drawable.card)
        v<ImageView>(R.id.alarm_icon).apply {
            setBackgroundResource(if (alarm) R.drawable.circle_white_soft else R.drawable.circle_gray)
            setImageResource(if (alarm) R.drawable.ic_alert else R.drawable.ic_shield)
        }
        v<TextView>(R.id.alarm_label).setTextColor(if (alarm) Color.parseColor("#D9FFFFFF") else color(R.color.secondary))
        v<TextView>(R.id.alarm_text).apply {
            text = if (alarm) activeText(errors.size) else "Нет"
            setTextColor(if (alarm) Color.WHITE else color(R.color.text))
        }

        // Причины аварии — каждая отдельной строкой с иконкой
        val box = v<LinearLayout>(R.id.errors)
        box.removeAllViews()
        box.visibility = if (alarm) View.VISIBLE else View.GONE
        errors.forEachIndexed { i, name ->
            if (i > 0) box.addView(View(this).apply {
                setBackgroundColor(color(R.color.separator))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1) / 2 + 1).apply { marginStart = dp(60) }
            })
            box.addView(errorRow(name))
        }

        // Управление
        seg(R.id.mode_auto, R.id.mode_manual, s[P.MODE], P.MODE)
        seg(R.id.sel_1, R.id.sel_2, s[P.SELECT], P.SELECT)
        stepperValue(P.SP1, R.id.sp1_val, R.id.sp1_label, active = !sel2)
        stepperValue(P.SP2, R.id.sp2_val, R.id.sp2_label, active = sel2)

        bindingSwitch = true
        v<Switch>(R.id.start_switch).apply {
            isChecked = P.isOn(s[P.START])
            isEnabled = prefs.ids.containsKey(P.START)
        }
        bindingSwitch = false

        val on = P.isOn(s.reset)
        v<TextView>(R.id.reset_btn).apply {
            text = if (s.reset == null) "—" else if (on) "1" else "0"
            setBackgroundResource(if (on) R.drawable.pill_on else R.drawable.pill_off)
            setTextColor(if (on) Color.WHITE else color(R.color.blue))
        }

        if (!writing) v<TextView>(R.id.status).text = prefs.lastStatus ?: ""
    }

    private fun activeText(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11 -> "$n активна"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "$n активны"
        else -> "$n активных"
    }

    private fun errorRow(name: String): View {
        val icon = when {
            name.contains("масл") -> R.drawable.ic_droplet
            name.contains("Перегрев") || name.contains("разожг") -> R.drawable.ic_flame
            else -> R.drawable.ic_alert
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(14), 0, dp(14), 0)
            addView(ImageView(this@MainActivity).apply {
                setBackgroundResource(R.drawable.circle_red_soft)
                setImageResource(icon)
                setColorFilter(color(R.color.red))
                setPadding(dp(7), dp(7), dp(7), dp(7))
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(TextView(this@MainActivity).apply {
                text = name
                setTextColor(color(R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        }
    }

    private fun seg(leftId: Int, rightId: Int, value: String?, code: String) {
        val right = P.isOn(value)
        val known = value != null
        listOf(leftId to (known && !right), rightId to (known && right)).forEach { (id, selected) ->
            v<TextView>(id).apply {
                setBackgroundResource(if (selected) R.drawable.seg_on else 0)
                typeface = android.graphics.Typeface.create("sans-serif-medium".takeIf { selected } ?: "sans-serif", android.graphics.Typeface.NORMAL)
                isEnabled = prefs.ids.containsKey(code)
            }
        }
    }

    // ---------- Уставки ----------

    private fun setupStepper(code: String, label: String, minusId: Int, valId: Int, plusId: Int) {
        v<TextView>(minusId).setOnClickListener { step(code, label, -0.5) }
        v<TextView>(plusId).setOnClickListener { step(code, label, +0.5) }
        v<TextView>(valId).setOnClickListener { inputValue(code, label) }
    }

    private fun stepperValue(code: String, valId: Int, labelId: Int, active: Boolean) {
        val p = pending[code]
        v<TextView>(valId).apply {
            text = if (p != null) fmt(p) + "°" else KotelWidget.formatTemp(prefs.state[code])
            setTextColor(if (p != null) color(R.color.blue) else color(if (active) R.color.text else R.color.secondary))
        }
        v<TextView>(labelId).setTextColor(color(if (active) R.color.text else R.color.secondary))
    }

    private fun step(code: String, label: String, delta: Double) {
        val base = pending[code] ?: P.num(prefs.state[code]) ?: return
        pending[code] = Math.round((base + delta) * 10) / 10.0
        render()
        // Пока жмут − / +, не пишем; через 1,5 с после последнего нажатия — спрашиваем
        pendingCommit[code]?.let { ui.removeCallbacks(it) }
        val r = Runnable {
            val value = pending[code] ?: return@Runnable
            ask(code, fmt(value), label, fmt(value) + "°", onCancel = { pending.remove(code); render() })
        }
        pendingCommit[code] = r
        ui.postDelayed(r, 1500)
    }

    private fun inputValue(code: String, label: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(KotelWidget.formatNum(prefs.state[code]))
            setSelectAllOnFocus(true)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(wrap)
            .setPositiveButton("Записать") { _, _ ->
                val n = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (n == null) { status("Введите число", error = true); return@setPositiveButton }
                write(code, fmt(n), label)
            }
            .setNegativeButton("Отмена", null)
            .show()
        input.requestFocus()
    }

    // ---------- Запись ----------

    /** Спросить подтверждение (если включено) и записать. */
    private fun ask(code: String, value: String, label: String, shown: String, onCancel: () -> Unit = {}) {
        if (writing) return
        if (!prefs.ids.containsKey(code)) { status("Параметр $label не найден у прибора", error = true); return }
        if (P.same(prefs.state[code], value)) { pending.remove(code); render(); return }
        if (!prefs.confirmWrite) { write(code, value, label); return }
        confirmCommand("$label → $shown", prefs.deviceName, "Записать", onCancel) {
            write(code, value, label)
        }
    }

    private fun write(code: String, value: String, label: String) {
        writing = true
        status("Записываю $label…")
        thread {
            val result = runCatching { Repo.write(prefs, code, value) }
            ui.post {
                writing = false
                pending.remove(code)
                result.fold(
                    onSuccess = { applied -> status(if (applied) "$label: записано" else "$label: команда в очереди, прибор применит её") },
                    onFailure = { status("Не записалось: ${it.message}", error = true) }
                )
                render()
                KotelWidget.render(this)
                DashboardMonitor.owenChanged(this)
            }
        }
    }

    private fun refresh(quiet: Boolean) {
        if (!quiet) status("Обновляю…")
        thread {
            Repo.refresh(prefs)
            ui.post {
                if (!quiet && prefs.lastStatus == null) status("")
                render()
                KotelWidget.render(this)
                DashboardMonitor.owenChanged(this)
            }
        }
    }

    private fun status(text: String, error: Boolean = false) {
        v<TextView>(R.id.status).apply {
            this.text = text
            setTextColor(color(if (error) R.color.red else R.color.secondary))
        }
    }

    // ---------- Вход, меню ----------

    private fun doLogin() {
        val email = v<EditText>(R.id.email).text.toString().trim()
        val password = v<EditText>(R.id.password).text.toString()
        val msg = v<TextView>(R.id.login_msg)
        val btn = v<Button>(R.id.login_btn)
        if (email.isEmpty() || password.isEmpty()) { msg.text = "Введите email и пароль"; return }
        btn.isEnabled = false
        msg.text = "Вхожу…"
        thread {
            val result = runCatching {
                val api = OwenApi(prefs)
                val token = api.login(email, password)
                prefs.login = email
                prefs.password = password
                prefs.token = token
                val found = api.autoSetup()
                if (found) Repo.refresh(prefs)
                found
            }
            ui.post {
                btn.isEnabled = true
                result.fold(
                    onSuccess = { found ->
                        if (found) {
                            msg.text = ""
                            showState()
                            KotelWidget.render(this)
                            DashboardMonitor.owenChanged(this)
                        } else {
                            msg.text = "Вход выполнен, но не нашёл прибор с параметрами ${P.TEMP} и ${P.RESET}"
                        }
                    },
                    onFailure = { msg.text = it.message ?: "Ошибка входа" }
                )
            }
        }
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Добавить виджет на экран")
        menu.menu.add(0, 2, 1, "Спрашивать подтверждение").apply { isCheckable = true; isChecked = prefs.confirmWrite }
        menu.menu.add(0, 3, 2, "Обновить")
        menu.menu.add(0, 4, 3, "Выйти (${prefs.login})")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> addWidget()
                2 -> { prefs.confirmWrite = !prefs.confirmWrite; KotelWidget.render(this) }
                3 -> refresh(quiet = false)
                4 -> logout()
            }
            true
        }
        menu.show()
    }

    private fun addWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        val provider = ComponentName(this, KotelWidget::class.java)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(provider, null, null)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Добавьте виджет вручную")
                .setMessage("Долгое нажатие на пустом месте рабочего стола → Виджеты → «Owen Настройки».")
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
                DashboardMonitor.clearOwen(this)
                v<EditText>(R.id.password).setText("")
                showState()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- helpers ----------

    private fun color(id: Int) = getColor(id)
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun fmt(n: Double) = String.format(Locale.US, "%.1f", n)
}
