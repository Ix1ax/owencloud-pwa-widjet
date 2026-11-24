package ru.ix1ax.kotel

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class YandexHomeActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            state.refresh()
            ui.postDelayed(this, 30_000)
        }
    }
    private lateinit var state: YandexHomeState
    private var renderedDevices: List<YandexDevice>? = null
    private val powerButtons = mutableListOf<Button>()

    private fun <T : View> view(id: Int): T = findViewById(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yandex_home)
        state = YandexHomeState.get(applicationContext)

        val code = view<EditText>(R.id.yandex_code)
        view<View>(R.id.yandex_back).setOnClickListener { finish() }
        view<View>(R.id.yandex_sign_in).setOnClickListener {
            val uri = state.beginAuthorization() ?: return@setOnClickListener
            code.text.clear()
            hideKeyboard()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) {
                view<TextView>(R.id.yandex_status).setText(R.string.yandex_no_browser)
            }
        }
        view<View>(R.id.yandex_connect).setOnClickListener {
            val entered = YandexHomeApi.readAuthorizationCode(code.text.toString())
            if (entered == null) {
                code.error = getString(R.string.yandex_invalid_code)
                return@setOnClickListener
            }
            hideKeyboard()
            state.connect(entered)
            code.text.clear()
        }
        view<View>(R.id.yandex_refresh).setOnClickListener { state.refresh() }
        view<View>(R.id.yandex_logout).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.yandex_logout_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.yandex_logout) { _, _ -> state.disconnect() }
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        state.onChange = ::render
        render()
    }

    override fun onResume() {
        super.onResume()
        poll.run()
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        super.onPause()
    }

    override fun onStop() {
        state.onChange = null
        super.onStop()
    }

    private fun render() {
        view<View>(R.id.yandex_login).visibility = if (state.configured && !state.connected) View.VISIBLE else View.GONE
        view<View>(R.id.yandex_code_panel).visibility = if (state.hasVerifier) View.VISIBLE else View.GONE
        view<View>(R.id.yandex_actions).visibility = if (state.connected) View.VISIBLE else View.GONE
        view<View>(R.id.yandex_control_help).visibility =
            if (state.connected && !state.canControl && state.devices.any { it.supportsPower }) View.VISIBLE else View.GONE
        listOf(R.id.yandex_sign_in, R.id.yandex_code, R.id.yandex_connect, R.id.yandex_refresh)
            .forEach { view<View>(it).isEnabled = !state.busy }
        view<TextView>(R.id.yandex_status).apply {
            text = when {
                !state.configured -> getString(R.string.yandex_not_configured)
                state.status == R.string.yandex_error_server ->
                    getString(state.status, state.httpStatus)
                state.status != 0 -> getString(state.status)
                state.devices.isEmpty() && state.updatedAt > 0 -> getString(R.string.yandex_empty)
                state.updatedAt > 0 -> getString(R.string.yandex_updated,
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.updatedAt)))
                else -> ""
            }
            state.requestId?.let { append("\n" + getString(R.string.yandex_error_request_id, it)) }
        }
        if (renderedDevices !== state.devices) {
            renderedDevices = state.devices
            val list = view<LinearLayout>(R.id.yandex_devices)
            list.removeAllViews()
            powerButtons.clear()
            state.devices.forEach { list.addView(deviceCard(it)) }
        }
        powerButtons.forEach { it.isEnabled = !state.busy }
    }

    private fun deviceCard(device: YandexDevice): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        setBackgroundResource(R.drawable.card)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(line(device.name ?: getString(R.string.yandex_device_fallback), 18f, R.color.text).apply {
            setTypeface(typeface, Typeface.BOLD)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        })
        if (device.location.isNotEmpty()) addView(line(device.location, 14f, R.color.secondary))
        if (device.readings.isEmpty()) addView(line(getString(R.string.yandex_no_readings), 14f, R.color.secondary))
        device.readings.forEach { reading ->
            val activeLeak = reading.metric == YandexMetric.WATER_LEAK && reading.value == "leak"
            val readingText = getString(R.string.yandex_reading, getString(reading.metric.label), reading.displayValue(this@YandexHomeActivity))
            addView(line(readingText, 16f, if (activeLeak) R.color.red else R.color.text).apply {
                setPadding(0, dp(8), 0, 0)
                if (activeLeak) setTypeface(typeface, Typeface.BOLD)
            })
            reading.updatedAt?.let {
                val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
                addView(line(date, 13f, R.color.secondary))
            }
        }
        if (state.canControl && device.supportsPower) {
            addView(LinearLayout(this@YandexHomeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(powerButton(device, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(powerButton(device, false), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }

    private fun powerButton(device: YandexDevice, enable: Boolean): Button {
        val action = getString(if (enable) R.string.yandex_turn_on else R.string.yandex_turn_off)
        val name = device.name ?: getString(R.string.yandex_device_fallback)
        return Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = action
            setTextColor(getColor(R.color.blue))
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener {
                AlertDialog.Builder(this@YandexHomeActivity)
                    .setTitle(getString(R.string.yandex_confirm_power, action, name))
                    .setMessage(device.location.takeIf { it.isNotEmpty() })
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(action) { _, _ ->
                        if (!state.setPower(device, enable)) {
                            Toast.makeText(this@YandexHomeActivity, R.string.yandex_power_retry, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
            }
            powerButtons.add(this)
        }
    }

    private fun line(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(color))
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value + 0.5f).toInt()

    private fun hideKeyboard() {
        currentFocus?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }
}

/** Состояние экрана переживает поворот телефона и возвращение из браузера. Все изменения выполняются в UI-потоке. */
private class YandexHomeState(private val context: Context) {
    private val clientId = context.getString(R.string.yandex_oauth_client_id)
    private val store = YandexHomeStore(context)
    private val ui = Handler(Looper.getMainLooper())
    private val sameClient = clientId.isNotEmpty() && clientId == store.clientId
    private var session = if (sameClient) store.session else null
    private var verifier = if (sameClient) store.verifier else null
    private var operation = 0

    val configured: Boolean get() = clientId.isNotEmpty()
    val connected: Boolean get() = session != null
    val canControl: Boolean get() = session?.canControl == true
    val hasVerifier: Boolean get() = verifier != null
    var devices: List<YandexDevice> = emptyList()
        private set
    var updatedAt = 0L
        private set
    var status = 0
        private set
    var requestId: String? = null
        private set
    var httpStatus = 0
        private set
    var busy = false
        private set
    var onChange: (() -> Unit)? = null

    init {
        if (configured && !sameClient) store.clearSession()
    }

    fun beginAuthorization(): Uri? {
        if (!configured || busy || connected) return null
        val next = YandexHomeApi.newVerifier()
        val stored = runCatching { store.saveVerifier(clientId, next) }.isSuccess
        if (stored) verifier = next
        showStatus(if (stored) 0 else R.string.yandex_error_storage)
        return if (stored) YandexHomeApi.authorizationUri(clientId, next) else null
    }

    fun connect(code: String) {
        val pending = verifier
        if (pending == null || clientId != store.clientId) {
            showStatus(R.string.yandex_get_new_code)
            return
        }
        request(R.string.yandex_connecting, { YandexHomeApi.exchangeCode(clientId, code, pending) }) { newSession ->
            if (runCatching { store.saveSession(newSession) }.isSuccess) {
                session = newSession
                verifier = null
                refresh()
            } else {
                showStatus(R.string.yandex_error_storage)
            }
        }
    }

    fun refresh(afterPower: Boolean = false) {
        val current = session ?: return
        if (busy) return
        if (current.expiresAt <= System.currentTimeMillis()) {
            disconnect(R.string.yandex_reconnect)
            return
        }
        val loading = if (afterPower) R.string.yandex_power_updating else R.string.yandex_loading
        request(loading, { YandexHomeApi.devices(current.accessToken) }) {
            devices = it
            updatedAt = System.currentTimeMillis()
            DashboardMonitor.acceptYandex(context, current, it)
            showStatus(if (afterPower) R.string.yandex_power_done else 0)
        }
    }

    fun setPower(device: YandexDevice, enabled: Boolean): Boolean {
        val current = session ?: return false
        val id = device.id ?: return false
        if (busy || !current.canControl || devices.none { it.id == id && it.supportsPower }) return false
        if (current.expiresAt <= System.currentTimeMillis()) {
            disconnect(R.string.yandex_reconnect)
            return false
        }
        request(R.string.yandex_power_sending, { YandexHomeApi.setPower(current.accessToken, id, enabled) }) {
            refresh(afterPower = true)
        }
        return true
    }

    fun disconnect(message: Int = 0) {
        operation++
        busy = false
        store.clearSession()
        DashboardMonitor.clearYandex(context)
        session = null
        verifier = null
        devices = emptyList()
        updatedAt = 0L
        showStatus(message)
    }

    private fun <T> request(loading: Int, fetch: () -> T, onSuccess: (T) -> Unit) {
        if (busy) return
        val id = ++operation
        busy = true
        showStatus(loading)
        thread {
            val result = runCatching(fetch)
            ui.post {
                if (id != operation) return@post
                busy = false
                result.fold(onSuccess) { showFailure(it, loading) }
            }
        }
    }

    private fun showFailure(error: Throwable, loading: Int) {
        val afterPower = loading == R.string.yandex_power_updating
        val loadingDevices = loading == R.string.yandex_loading || afterPower
        val sendingPower = loading == R.string.yandex_power_sending
        if (loadingDevices) {
            devices = emptyList()
            updatedAt = 0L
        }
        if (error is YandexRequestFailure) {
            if ((loadingDevices || sendingPower) && error.status == 401) {
                disconnect(R.string.yandex_reconnect)
                return
            }
            if (loadingDevices && error.status == 403) {
                disconnect(R.string.yandex_error_scope)
                return
            }
            val message = when {
                afterPower -> R.string.yandex_power_refresh_failed
                sendingPower -> when {
                    error.status == 403 -> R.string.yandex_power_forbidden
                    error.deviceError == "DEVICE_UNREACHABLE" -> R.string.yandex_power_unreachable
                    else -> R.string.yandex_power_failed
                }
                else -> when (error.oauthError) {
                    "bad_verification_code", "invalid_grant" -> R.string.yandex_error_code
                    "invalid_client", "unauthorized_client" -> R.string.yandex_error_oauth_client
                    "invalid_scope" -> R.string.yandex_error_scope
                    else -> if (error.status in 200..299) R.string.yandex_error_response else R.string.yandex_error_server
                }
            }
            showStatus(message, error.status, error.requestId)
            return
        }
        val message = when {
            afterPower -> R.string.yandex_power_refresh_failed
            sendingPower -> R.string.yandex_power_unconfirmed
            error is IOException -> R.string.yandex_error_network
            else -> R.string.yandex_error_response
        }
        showStatus(message)
    }

    private fun showStatus(message: Int, httpStatus: Int = 0, requestId: String? = null) {
        status = message
        this.httpStatus = httpStatus
        this.requestId = requestId
        onChange?.invoke()
    }

    companion object {
        private var instance: YandexHomeState? = null

        fun get(context: Context): YandexHomeState =
            instance ?: YandexHomeState(context).also { instance = it }
    }
}
