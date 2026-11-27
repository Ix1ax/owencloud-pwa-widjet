package ru.ix1ax.kotel

import android.content.Context
import org.json.JSONObject

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
        get() = sp.getString("deviceName", null) ?: "Котельная"
        set(v) = sp.edit().putString("deviceName", v).apply()

    /** Код параметра (P512…) → его id в OwenCloud. */
    var ids: Map<String, Long>
        get() = readMap("ids") { o, k -> o.getLong(k) }
        set(v) = sp.edit().putString("ids", JSONObject(v as Map<*, *>).toString()).apply()

    /** Последние значения: код → значение. */
    var values: Map<String, String>
        get() = readMap("values") { o, k -> o.getString(k) }
        set(v) = sp.edit().putString("values", JSONObject(v as Map<*, *>).toString()).apply()

    var lastTime: Long
        get() = sp.getLong("lastTime", 0)
        set(v) = sp.edit().putLong("lastTime", v).apply()
    /** Статус прибора из OwenCloud: online / offline / alarm / unreadalarm. */
    var deviceStatus: String?
        get() = sp.getString("deviceStatus", null)
        set(v) = sp.edit().putString("deviceStatus", v).apply()

    /**
     * Прибор не на связи: OwenCloud сообщает offline или последнее показание
     * температуры старше 15 минут (в том числе когда нет интернета на телефоне).
     */
    val offline: Boolean
        get() = deviceStatus == "offline" ||
            (lastTime > 0 && System.currentTimeMillis() - lastTime * 1000 > STALE_MS)

    var lastStatus: String?
        get() = sp.getString("lastStatus", null)
        set(v) = sp.edit().putString("lastStatus", v).apply()

    var confirmWrite: Boolean
        get() = sp.getBoolean("confirmWrite", true)
        set(v) = sp.edit().putBoolean("confirmWrite", v).apply()

    val loggedIn: Boolean get() = login != null && password != null
    val isConfigured: Boolean get() = loggedIn && ids.containsKey(P.TEMP) && ids.containsKey(P.RESET)

    val state: State get() = State(values)

    private fun <T> readMap(key: String, get: (JSONObject, String) -> T): Map<String, T> {
        val o = runCatching { JSONObject(sp.getString(key, null) ?: return emptyMap()) }.getOrNull()
            ?: return emptyMap()
        return o.keys().asSequence().associateWith { get(o, it) }
    }

    fun clear() = sp.edit().clear().putBoolean("confirmWrite", confirmWrite).apply()

    companion object {
        const val STALE_MS = 15 * 60 * 1000L
    }
}
