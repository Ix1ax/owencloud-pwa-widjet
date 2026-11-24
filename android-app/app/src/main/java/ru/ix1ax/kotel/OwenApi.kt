package ru.ix1ax.kotel

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(message: String, val status: Int = 0) : Exception(message)

/** Последнее значение параметра из OwenCloud. */
data class Reading(val value: String, val time: Long)

/**
 * Клиент OwenCloud API (https://api.owencloud.ru/).
 * Все методы блокирующие — вызывать не из главного потока.
 */
class OwenApi(private val prefs: Prefs) {

    private val base = "https://api.owencloud.ru/v1/"

    private fun post(path: String, body: String, token: String?): Pair<Int, String> {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } catch (e: IOException) {
            throw ApiException("Нет связи с OwenCloud")
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(code: Int, text: String): String =
        runCatching { JSONObject(text).optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "Ошибка $code"

    fun login(email: String, password: String): String {
        val body = JSONObject().put("login", email).put("password", password).toString()
        val (code, text) = post("auth/open", body, null)
        val token = runCatching { JSONObject(text).optString("token") }.getOrNull()
        if (code >= 400 || token.isNullOrBlank()) {
            throw ApiException(if (code == 401 || code == 0) "Неверный email или пароль" else errorMessage(code, text), 401)
        }
        return token
    }

    private fun relogin() {
        val email = prefs.login ?: throw ApiException("Нужно войти в приложении", 401)
        val password = prefs.password ?: throw ApiException("Нужно войти в приложении", 401)
        val token = login(email, password)
        if (prefs.login != email || prefs.password != password) throw ApiException("Аккаунт OwenCloud изменился", 401)
        prefs.token = token
    }

    /** Запрос с токеном; при 401 один раз входит заново сохранённым паролем. */
    private fun call(path: String, body: String): String {
        if (prefs.token == null) relogin()
        var (code, text) = post(path, body, prefs.token)
        if (code == 401) {
            relogin()
            val retry = post(path, body, prefs.token)
            code = retry.first; text = retry.second
        }
        if (code >= 400) throw ApiException(errorMessage(code, text), code)
        return text
    }

    data class Device(val id: Long, val name: String)
    data class Param(val id: Long, val code: String, val name: String)

    fun devices(): List<Device> {
        val arr = JSONArray(call("device/index", "{}"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Device(o.getLong("id"), o.optString("name", "Прибор"))
        }
    }

    /** Статус прибора (online / offline / alarm …) или null, если не удалось узнать. */
    fun deviceStatus(deviceId: Long): String? {
        val body = JSONObject().put("device_ids", JSONArray().put(deviceId)).toString()
        val arr = JSONArray(call("device/index", body))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optLong("id") == deviceId) return o.optString("status").ifBlank { null }
        }
        return null
    }

    fun params(deviceId: Long): List<Param> {
        val arr = JSONObject(call("device/$deviceId", "[]")).optJSONArray("parameters") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Param(o.getLong("id"), o.optString("code"), o.optString("name"))
        }
    }

    fun lastData(ids: List<Long>): Map<Long, Reading> {
        val body = JSONObject().put("ids", JSONArray(ids)).toString()
        val arr = JSONArray(call("parameters/last-data", body))
        val out = HashMap<Long, Reading>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val values = o.optJSONArray("values") ?: continue
            if (values.length() == 0) continue
            val v = values.getJSONObject(0)
            out[o.getLong("id")] = Reading(v.optString("v"), v.optLong("d"))
        }
        return out
    }

    fun write(paramId: Long, value: String) {
        val body = JSONObject()
            .put("sms_tag", "").put("sms_code", "")
            .put("timeout", 60).put("sync", false)
            .put("data", JSONArray().put(JSONObject().put("id", paramId).put("value", value)))
            .toString()
        val text = call("parameters/write-data", body)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: throw ApiException(text.ifBlank { "Ошибка записи" })
        if (obj.optString("status") == "error") {
            val sms = "В OwenCloud включено SMS-подтверждение записи"
            throw ApiException(
                when (obj.optString("code")) {
                    "wrong_sms_tag", "expired_sms_code", "wrong_sms_code" -> sms
                    "parameter_not_writable" -> "Параметр недоступен для записи"
                    "invalid_value" -> "Недопустимое значение"
                    "can_not_write" -> "Прибор не принял запись"
                    else -> obj.optString("message", "Ошибка записи")
                }
            )
        }
    }

    /** Находит прибор с параметрами P512 (температура) и P527 (Reset) и запоминает id всех нужных полей. */
    fun autoSetup(): Boolean {
        val login = prefs.login
        val password = prefs.password
        for (d in devices()) {
            val byCode = params(d.id).associateBy { it.code }
            if (P.TEMP !in byCode || P.RESET !in byCode) continue
            if (prefs.login != login || prefs.password != password) return false
            prefs.deviceId = d.id
            prefs.deviceName = d.name
            prefs.ids = P.ALL.mapNotNull { code -> byCode[code]?.let { code to it.id } }.toMap()
            return true
        }
        return false
    }
}

/** Общая логика для приложения и виджета. Методы блокирующие. */
object Repo {
    /** Перечитать данные и вернуть коды параметров, полученных именно в этом запросе. */
    fun refresh(prefs: Prefs): Set<String> {
        val login = prefs.login ?: return emptySet()
        val password = prefs.password ?: return emptySet()
        try {
            val api = OwenApi(prefs)
            if (!prefs.isConfigured || prefs.ids.size < P.ALL.size / 2) {
                if (!api.autoSetup()) throw ApiException("Не нашёл прибор с параметрами ${P.TEMP} и ${P.RESET}")
            }
            val deviceId = prefs.deviceId
            val ids = prefs.ids
            val data = api.lastData(ids.values.toList())
            val deviceStatus = runCatching { api.deviceStatus(deviceId) }.getOrNull()
            if (prefs.login != login || prefs.password != password || prefs.deviceId != deviceId) return emptySet()
            val values = HashMap(prefs.values)
            for ((code, id) in ids) data[id]?.let { values[code] = it.value }
            prefs.values = values
            data[ids[P.TEMP]]?.let { prefs.lastTime = it.time }
            // Статус прибора — необязательный: если не удалось узнать, опираемся на время показаний
            prefs.deviceStatus = deviceStatus
            prefs.lastStatus = null
            return ids.filterValues { it in data }.keys
        } catch (e: Exception) {
            if (prefs.login == login && prefs.password == password) prefs.lastStatus = e.message ?: "Ошибка"
            return emptySet()
        }
    }

    /**
     * Записать значение и дождаться, пока прибор его применит.
     * Возвращает true, если новое значение уже прочитано с прибора.
     */
    fun write(prefs: Prefs, code: String, value: String): Boolean {
        val id = prefs.ids[code] ?: throw ApiException("Параметр $code не найден у прибора")
        val login = prefs.login
        val deviceId = prefs.deviceId
        OwenApi(prefs).write(id, value)
        for (delay in longArrayOf(2500, 3500)) {
            Thread.sleep(delay)
            if (prefs.login != login || prefs.deviceId != deviceId) return false
            if (code in refresh(prefs) && P.same(prefs.values[code], value)) return true
        }
        return false
    }
}
