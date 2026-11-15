package ru.ix1ax.kotel

import android.content.Context

/** Настройки хранятся в приватной памяти приложения на телефоне. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("kotel", Context.MODE_PRIVATE)

    var login: String?
        get() = sp.getString("login", null)
        set(v) = sp.edit().putString("login", v).apply()
    var password: String?
        get() = sp.getString("password", null)
        set(v) = sp.edit().putString("password", v).apply()
    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()

    var deviceId: Long
        get() = sp.getLong("deviceId", 0)
        set(v) = sp.edit().putLong("deviceId", v).apply()
    var deviceName: String
        get() = sp.getString("deviceName", null) ?: "Котёл"
        set(v) = sp.edit().putString("deviceName", v).apply()
    var tempId: Long
        get() = sp.getLong("tempId", 0)
        set(v) = sp.edit().putLong("tempId", v).apply()
    var resetId: Long
        get() = sp.getLong("resetId", 0)
        set(v) = sp.edit().putLong("resetId", v).apply()

    var confirmWrite: Boolean
        get() = sp.getBoolean("confirmWrite", true)
        set(v) = sp.edit().putBoolean("confirmWrite", v).apply()

    // Последние значения — чтобы виджет сразу что-то показывал
    var lastTemp: String?
        get() = sp.getString("lastTemp", null)
        set(v) = sp.edit().putString("lastTemp", v).apply()
    var lastReset: String?
        get() = sp.getString("lastReset", null)
        set(v) = sp.edit().putString("lastReset", v).apply()
    var lastTime: Long
        get() = sp.getLong("lastTime", 0)
        set(v) = sp.edit().putLong("lastTime", v).apply()
    var lastStatus: String?
        get() = sp.getString("lastStatus", null)
        set(v) = sp.edit().putString("lastStatus", v).apply()

    val isConfigured: Boolean
        get() = password != null && tempId != 0L && resetId != 0L

    fun clear() = sp.edit().clear().apply()
}
