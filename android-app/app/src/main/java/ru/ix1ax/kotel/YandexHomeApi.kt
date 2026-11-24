package ru.ix1ax.kotel

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeParseException

internal data class YandexSession(val accessToken: String, val expiresAt: Long, val canControl: Boolean)

internal enum class YandexMetric {
    WATER_LEAK, TEMPERATURE, HUMIDITY, BATTERY, POWER
}

internal data class YandexReading(
    val metric: YandexMetric,
    val value: Any?,
    val unit: String?,
    val updatedAt: Long?
)

internal data class YandexDevice(
    val id: String?,
    val name: String?,
    val location: String,
    val readings: List<YandexReading>
) {
    val supportsPower: Boolean get() = id != null && readings.any { it.metric == YandexMetric.POWER }
}

internal class YandexRequestFailure(
    val status: Int,
    val oauthError: String? = null,
    val requestId: String? = null,
    val deviceError: String? = null
) : Exception()

internal object YandexHomeApi {
    private const val OAUTH = "https://oauth.yandex.ru"
    private const val SMART_HOME = "https://api.iot.yandex.net/v1.0"
    private const val REDIRECT = "$OAUTH/verification_code"
    private const val VIEW_SCOPE = "iot:view"
    private const val CONTROL_SCOPE = "iot:control"
    private const val POWER = "devices.capabilities.on_off"
    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    fun newVerifier(): String = Base64.encodeToString(
        ByteArray(32).also(SecureRandom()::nextBytes), BASE64_FLAGS
    )

    fun authorizationUri(clientId: String, verifier: String): Uri {
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Uri.parse("$OAUTH/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("scope", VIEW_SCOPE)
            .appendQueryParameter("optional_scope", CONTROL_SCOPE)
            .appendQueryParameter("force_confirm", "yes")
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", Base64.encodeToString(challenge, BASE64_FLAGS))
            .build()
    }

    fun exchangeCode(clientId: String, code: String, verifier: String): YandexSession {
        val body = mapOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "code_verifier" to verifier
        ).entries.joinToString("&") { (name, value) ->
            "$name=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        val response = request("$OAUTH/token", body = body, contentType = "application/x-www-form-urlencoded")
        val scopes = response.nonBlank("scope")?.split(' ')
        if (scopes != null && VIEW_SCOPE !in scopes) {
            throw YandexRequestFailure(HttpURLConnection.HTTP_FORBIDDEN, "invalid_scope")
        }
        val expiresIn = response.getLong("expires_in")
        val token = response.nonBlank("access_token")
        if (token == null || expiresIn <= 0 || expiresIn > Long.MAX_VALUE / 2000) {
            throw JSONException("Invalid OAuth token response")
        }
        return YandexSession(token, System.currentTimeMillis() + expiresIn * 1000, scopes == null || CONTROL_SCOPE in scopes)
    }

    fun devices(token: String): List<YandexDevice> =
        parseDevices(request("$SMART_HOME/user/info", token = token))

    fun setPower(token: String, deviceId: String, enabled: Boolean) {
        val response = request(
            "$SMART_HOME/devices/actions",
            token = token,
            body = powerRequest(deviceId, enabled).toString(),
            contentType = "application/json"
        )
        checkPowerResult(response, deviceId)
    }

    internal fun powerRequest(deviceId: String, enabled: Boolean): JSONObject {
        val state = JSONObject().put("instance", "on").put("value", enabled)
        val action = JSONObject().put("type", POWER).put("state", state)
        val device = JSONObject().put("id", deviceId).put("actions", JSONArray().put(action))
        return JSONObject().put("devices", JSONArray().put(device))
    }

    internal fun checkPowerResult(response: JSONObject, deviceId: String) {
        val device = response.optJSONArray("devices").objects().firstOrNull { it.nonBlank("id") == deviceId }
        val result = device?.optJSONArray("capabilities").objects()
            .filter { it.optString("type") == POWER }
            .mapNotNull { it.optJSONObject("state") }
            .firstOrNull { it.optString("instance") == "on" }
            ?.optJSONObject("action_result")
            ?: device?.optJSONObject("action_result")
        if (response.optString("status") != "ok" || result?.optString("status") != "DONE") {
            throw YandexRequestFailure(
                HttpURLConnection.HTTP_OK,
                requestId = response.nonBlank("request_id"),
                deviceError = result?.nonBlank("error_code") ?: device?.nonBlank("error_code")
            )
        }
    }

    internal fun parseDevices(response: JSONObject): List<YandexDevice> {
        val rooms = namesById(response.optJSONArray("rooms"))
        val homes = namesById(response.optJSONArray("households"))
        return response.getJSONArray("devices").objects().map { device ->
            val readings = device.optJSONArray("properties").objects().mapNotNull(::parseProperty)
            val power = device.optJSONArray("capabilities").objects()
                .firstOrNull { it.optString("type") == POWER }
                ?.let { parseReading(it, YandexMetric.POWER) }
            YandexDevice(
                id = device.nonBlank("id"),
                name = device.nonBlank("name"),
                location = listOfNotNull(
                    homes[device.nonBlank("household_id")],
                    rooms[device.nonBlank("room")]
                ).joinToString(" · "),
                readings = (readings + listOfNotNull(power)).sortedBy { it.metric }
            )
        }
    }

    private fun parseProperty(property: JSONObject): YandexReading? {
        val instance = property.optJSONObject("parameters")?.nonBlank("instance")
            ?: property.optJSONObject("state")?.nonBlank("instance")
        val metric = when (property.optString("type")) {
            "devices.properties.event" -> YandexMetric.WATER_LEAK.takeIf { instance == "water_leak" }
            "devices.properties.float" -> when (instance) {
                "temperature" -> YandexMetric.TEMPERATURE
                "humidity" -> YandexMetric.HUMIDITY
                "battery_level" -> YandexMetric.BATTERY
                else -> null
            }
            else -> null
        } ?: return null
        return parseReading(property, metric)
    }

    private fun parseReading(property: JSONObject, metric: YandexMetric): YandexReading {
        val raw = property.optJSONObject("state")?.opt("value")
        val value = when (metric) {
            YandexMetric.WATER_LEAK -> raw.takeIf { it == "dry" || it == "leak" }
            YandexMetric.POWER -> raw as? Boolean
            else -> (raw as? Number)?.toDouble()?.takeIf(Double::isFinite)
        }
        return YandexReading(
            metric, value, property.optJSONObject("parameters")?.nonBlank("unit"),
            if (value != null) timestamp(property.opt("last_updated")) else null
        )
    }

    private fun timestamp(value: Any?): Long? {
        val seconds = (value as? Number)?.toDouble() ?: (value as? String)?.toDoubleOrNull()
        if (seconds != null) return seconds.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() }
        if (value !is String) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun namesById(array: JSONArray?): Map<String, String> =
        array.objects().mapNotNull { item ->
            val id = item.nonBlank("id") ?: return@mapNotNull null
            val name = item.nonBlank("name") ?: return@mapNotNull null
            id to name
        }.toMap()

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

    private fun JSONObject.nonBlank(key: String): String? =
        (opt(key) as? String)?.takeIf { it.isNotBlank() }

    private fun request(
        url: String,
        token: String? = null,
        body: String? = null,
        contentType: String? = null
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                val encoded = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(encoded.size)
                connection.outputStream.use { it.write(encoded) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use {
                    try {
                        JSONObject(it.readText())
                    } catch (_: JSONException) {
                        null
                    }
                }
                throw YandexRequestFailure(status, error?.nonBlank("error"), error?.nonBlank("request_id"))
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                JSONObject(it.readText())
            }
            if (response.optString("status") == "error") {
                throw YandexRequestFailure(status, requestId = response.nonBlank("request_id"))
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}
