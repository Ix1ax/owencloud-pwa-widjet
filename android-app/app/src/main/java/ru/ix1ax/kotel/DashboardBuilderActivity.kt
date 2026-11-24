package ru.ix1ax.kotel

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class DashboardBuilderActivity : Activity() {
    private val id by lazy { DashboardWidget.widgetId(intent) }
    private val existing get() = id != AppWidgetManager.INVALID_APPWIDGET_ID
    private val store by lazy { DashboardStore(this) }
    private lateinit var page: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var name: EditText
    private lateinit var layout: Spinner
    private lateinit var selected: LinearLayout
    private val tiles = mutableListOf<DashboardTile>()
    private var editing = false
    private var loadingYandex = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED, widgetResult())
        if (existing && !DashboardWidget.isDashboard(this, id)) {
            finish()
            return
        }
        scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            isFillViewport = true
        }
        setContentView(scroll)
        val draft = DashboardConfig.decode(savedInstanceState?.getString("draft"))
        if (existing || draft != null) {
            showEditor(draft ?: store.config(id) ?: store.pendingDraft() ?: DashboardConfig())
        } else showOverview()
    }

    override fun onResume() {
        super.onResume()
        if (!::page.isInitialized) return
        if (editing) renderTiles() else showOverview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (editing) outState.putString("draft", draft().encode())
        super.onSaveInstanceState(outState)
    }

    private fun showOverview() {
        editing = false
        resetPage(getString(R.string.dashboard_builder))
        button(page, "Создать виджет", primary = true) { showEditor(store.pendingDraft() ?: DashboardConfig()) }
        val ids = DashboardWidget.ids(this)
        if (ids.isNotEmpty()) {
            page.addView(heading("На рабочем столе", 20f))
            ids.forEach { id ->
                val config = store.config(id)
                button(page, config?.title ?: getString(R.string.dashboard_setup)) {
                    startActivity(Intent(this, DashboardBuilderActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                }
            }
        }
        page.addView(heading("Фоновые уведомления", 20f))
        page.addView(body("Аварии OwenCloud и протечки Яндекса. Android проверяет устройства в фоне примерно каждые 15 минут или позже."))
        page.addView(Switch(this).apply {
            text = "Уведомлять на телефоне"
            minHeight = dp(48)
            isChecked = DashboardMonitor.alertsRequested(this@DashboardBuilderActivity)
            setOnCheckedChangeListener { _, enabled ->
                if (!enabled) DashboardMonitor.setAlerts(this@DashboardBuilderActivity, false)
                else enableAlerts()
            }
        })
        if (DashboardMonitor.alertsRequested(this) && !DashboardMonitor.canNotify(this)) {
            button(page, "Разрешить уведомления в Android") { openNotificationSettings() }
        }
    }

    private fun showEditor(config: DashboardConfig) {
        editing = true
        resetPage(if (existing) "Настройка виджета" else "Новый виджет")
        tiles.clear()
        tiles.addAll(config.tiles)
        name = EditText(this).apply {
            hint = "Название виджета"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(40))
            setText(config.title)
            setSelectAllOnFocus(true)
            minHeight = dp(52)
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundResource(R.drawable.field)
        }
        page.addView(name, LinearLayout.LayoutParams(-1, -2))
        layout = Spinner(this).apply {
            val options = ArrayAdapter(this@DashboardBuilderActivity, android.R.layout.simple_spinner_item,
                listOf("Один столбец", "Два столбца"))
            options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            adapter = options
            setSelection(if (config.grid) 1 else 0)
            contentDescription = "Раскладка виджета"
            minimumHeight = dp(48)
        }
        page.addView(layout)
        button(page, "Добавить карточку") { showSourcePicker() }
        selected = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(selected)
        button(page, if (existing) "Сохранить виджет" else "Добавить на рабочий стол", primary = true) { save() }
        renderTiles()
    }

    private fun renderTiles() {
        selected.removeAllViews()
        if (tiles.isEmpty()) {
            selected.addView(body("Выберите показания или кнопки для этого виджета."))
            return
        }
        val data = DashboardData(this)
        tiles.forEachIndexed { index, tile ->
            val card = data.card(tile)
            selected.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(4))
                setBackgroundResource(R.drawable.card)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                addView(heading(card.title, 16f))
                addView(body(card.value))
                addView(LinearLayout(this@DashboardBuilderActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    tool(this, "↑", "Выше") { move(index, index - 1) }.isEnabled = index > 0
                    tool(this, "↓", "Ниже") { move(index, index + 1) }.isEnabled = index < tiles.lastIndex
                    tool(this, "Убрать", "Убрать ${card.title}") {
                        tiles.removeAt(index)
                        renderTiles()
                    }
                })
            })
        }
    }

    private fun move(index: Int, to: Int) {
        if (to !in tiles.indices) return
        tiles.add(to, tiles.removeAt(index))
        renderTiles()
    }

    private fun showSourcePicker() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(name.windowToken, 0)
        page.requestFocus()
        if (tiles.size >= DashboardConfig.MAX_TILES) {
            toast("В один виджет можно добавить до ${DashboardConfig.MAX_TILES} карточек")
            return
        }
        AlertDialog.Builder(this).setTitle("Источник")
            .setItems(arrayOf("OwenCloud", "Умный дом Яндекса")) { _, index ->
                if (index == 0) pickOwen() else pickYandex()
            }.show()
    }

    private fun pickOwen() {
        val options = OwenTile.entries.filter { DashboardTile(it.name) !in tiles }
        if (options.isEmpty()) {
            toast("Все карточки OwenCloud уже добавлены")
            return
        }
        AlertDialog.Builder(this).setTitle("Карточки OwenCloud")
            .setItems(options.map { if (it.parameter == null) it.title else "${it.title} · управление" }.toTypedArray()) { _, index ->
                add(DashboardTile(options[index].name))
            }.show()
    }

    private fun pickYandex() {
        val data = DashboardData(this)
        if (data.account == null) {
            AlertDialog.Builder(this).setTitle("Подключите Яндекс")
                .setPositiveButton("Войти") { _, _ -> startActivity(Intent(this, YandexHomeActivity::class.java)) }
                .setNegativeButton("Отмена", null).show()
            return
        }
        val devices = data.snapshot?.devices
        if (devices != null) {
            showDevices(devices)
            return
        }
        loadYandex { showDevices(DashboardData(this).snapshot?.devices.orEmpty()) }
    }

    private fun loadYandex(onReady: () -> Unit) {
        if (loadingYandex) return
        loadingYandex = true
        toast("Загружаем устройства Яндекса…")
        val context = applicationContext
        thread {
            val loaded = DashboardMonitor.refreshYandex(context)
            runOnUiThread {
                loadingYandex = false
                if (!isFinishing && !isDestroyed) {
                    if (loaded) onReady() else toast("Не удалось загрузить устройства Яндекса")
                }
            }
        }
    }

    private fun showDevices(devices: List<YandexDevice>) {
        val options = devices.filter { it.id != null }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Устройства Яндекса")
            .setNeutralButton("Обновить") { _, _ -> loadYandex { showDevices(DashboardData(this).snapshot?.devices.orEmpty()) } }
        if (options.isEmpty()) dialog.setMessage("В этом аккаунте нет доступных устройств")
        else dialog.setItems(options.map {
            listOfNotNull(it.name ?: getString(R.string.yandex_device_fallback), it.location.takeIf(String::isNotBlank))
                .joinToString(" · ")
        }
            .toTypedArray()) { _, index -> pickYandexCard(options[index]) }
        dialog.show()
    }

    private fun pickYandexCard(device: YandexDevice) {
        val id = device.id ?: return
        val options = buildList {
            add(DashboardTile(DashboardTile.YANDEX_STATUS, id) to "Все показания устройства")
            if (device.readings.size > 1) device.readings.forEach {
                add(DashboardTile.forMetric(it.metric, id) to getString(it.metric.label))
            }
            if (device.supportsPower) add(DashboardTile(DashboardTile.YANDEX_POWER, id) to "Включение и выключение")
        }.filter { it.first !in tiles }
        if (options.isEmpty()) {
            toast("Карточки этого устройства уже добавлены")
            return
        }
        AlertDialog.Builder(this).setTitle(device.name ?: getString(R.string.yandex_device_fallback))
            .setItems(options.map { it.second }.toTypedArray()) { _, index -> add(options[index].first) }.show()
    }

    private fun add(tile: DashboardTile) {
        tiles.add(tile)
        renderTiles()
    }

    private fun draft() = DashboardConfig(
        name.text.toString().trim().ifBlank { DashboardConfig().title }, layout.selectedItemPosition == 1, tiles.toList()
    )

    private fun save() {
        val config = draft()
        if (config.tiles.isEmpty()) {
            toast("Добавьте хотя бы одну карточку")
            return
        }
        if (existing) {
            if (!DashboardWidget.isDashboard(this, id)) {
                finish()
                return
            }
            val pending = if (store.config(id) == null) store.pendingDraft() else null
            store.save(id, config)
            pending?.let(store::clearDraft)
            DashboardWidget.render(this, id)
            DashboardMonitor.schedule(this)
            DashboardMonitor.requestRefresh(this)
            setResult(RESULT_OK, widgetResult())
            finish()
        } else {
            store.saveDraft(config)
            if (DashboardWidget.pin(this, config)) {
                toast("Подтвердите добавление на рабочий стол")
                finish()
            } else {
                AlertDialog.Builder(this).setTitle("Добавление вручную")
                    .setMessage("На рабочем столе откройте «Виджеты» и добавьте «Мой умный дом». Конструктор откроет сохранённый черновик.")
                    .setPositiveButton("Понятно", null).show()
            }
        }
    }

    private fun widgetResult() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)

    private fun enableAlerts() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        } else {
            DashboardMonitor.setAlerts(this, true)
            if (!DashboardMonitor.canNotify(this)) openNotificationSettings()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 41) return
        DashboardMonitor.setAlerts(this, true)
        showOverview()
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun resetPage(title: String) {
        scroll.removeAllViews()
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }
        scroll.addView(page)
        tool(page, "Назад", "Назад") { if (editing && !existing) showOverview() else finish() }
        page.addView(heading(title, 28f))
        scroll.scrollTo(0, 0)
    }

    private fun heading(value: String, size: Float) = body(value).apply {
        textSize = size
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun body(value: String) = TextView(this).apply {
        text = value
        setTextColor(getColor(R.color.text))
        textSize = 15f
    }

    private fun button(parent: LinearLayout, value: String, primary: Boolean = false, action: () -> Unit) =
        Button(this).apply {
            text = value
            isAllCaps = false
            minHeight = dp(50)
            stateListAnimator = null
            setBackgroundResource(if (primary) R.drawable.btn_primary else R.drawable.card)
            setTextColor(if (primary) android.graphics.Color.WHITE else getColor(R.color.blue))
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { action() }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        }

    private fun tool(parent: LinearLayout, value: String, label: String, action: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = value
            contentDescription = label
            isAllCaps = false
            setTextColor(getColor(R.color.blue))
            minHeight = dp(48)
            setOnClickListener { action() }
            parent.addView(this, LinearLayout.LayoutParams(-2, -2))
        }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    private fun dp(value: Int) = (resources.displayMetrics.density * value + 0.5f).toInt()
}
