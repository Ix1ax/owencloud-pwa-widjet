package ru.ix1ax.kotel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal enum class OwenTile(val title: String, val parameter: String? = null) {
    TEMPERATURE("Температура"),
    BURNER("Горелка"),
    ALARM("Аварии"),
    MODE("Режим"),
    SETPOINT("Активная уставка"),
    RESET("Сброс", P.RESET),
    START("Удалённый старт", P.START)
}

internal data class DashboardTile(val kind: String, val deviceId: String = "") {
    val owen: OwenTile? get() = OwenTile.entries.firstOrNull { it.name == kind }
    val yandexMetric: YandexMetric? get() = YandexMetric.entries.firstOrNull { "Y_${it.name}" == kind }
    val yandex: Boolean get() = kind == YANDEX_STATUS || kind == YANDEX_POWER || yandexMetric != null

    companion object {
        const val YANDEX_STATUS = "YANDEX_STATUS"
        const val YANDEX_POWER = "YANDEX_POWER"
        fun forMetric(metric: YandexMetric, id: String) = DashboardTile("Y_${metric.name}", id)
    }
}

internal data class DashboardConfig(
    val title: String = "Мой умный дом",
    val grid: Boolean = false,
    val tiles: List<DashboardTile> = emptyList()
) {
    fun encode(): String = JSONObject()
        .put("title", title)
        .put("grid", grid)
        .put("tiles", JSONArray(tiles.map { JSONObject().put("kind", it.kind).put("device", it.deviceId) }))
        .toString()

    companion object {
        const val MAX_TILES = 12

        fun decode(raw: String?): DashboardConfig? = runCatching {
            val json = JSONObject(raw ?: return null)
            val tiles = json.getJSONArray("tiles").objects().mapNotNull {
                val tile = DashboardTile(it.optString("kind"), it.optString("device"))
                tile.takeIf { it.owen != null || (it.yandex && it.deviceId.isNotBlank()) }
            }
            DashboardConfig(
                json.optString("title").trim().take(40).ifBlank { DashboardConfig().title },
                json.optBoolean("grid"),
                tiles.distinct().take(MAX_TILES)
            )
        }.getOrNull()
    }
}

internal data class DashboardSnapshot(val devices: List<YandexDevice>, val fetchedAt: Long)

internal class DashboardStore(context: Context) {
    val prefs = context.getSharedPreferences("dashboard_widgets", Context.MODE_PRIVATE)

    fun config(id: Int): DashboardConfig? = DashboardConfig.decode(prefs.getString("config.$id", null))
    fun save(id: Int, config: DashboardConfig) {
        prefs.edit().putString("config.$id", config.encode()).apply()
    }
    fun pendingDraft() = DashboardConfig.decode(prefs.getString("draft", null))
    fun saveDraft(config: DashboardConfig) {
        prefs.edit().putString("draft", config.encode()).apply()
    }
    fun clearDraft(config: DashboardConfig) {
        if (pendingDraft() == config) prefs.edit().remove("draft").apply()
    }
    fun delete(ids: IntArray) {
        prefs.edit().apply { ids.forEach { remove("config.$it") } }.apply()
    }
    fun restore(oldIds: IntArray, newIds: IntArray) {
        val saved = oldIds.map(::config)
        prefs.edit().apply {
            oldIds.forEach { remove("config.$it") }
            newIds.zip(saved).forEach { (id, config) -> config?.let { putString("config.$id", it.encode()) } }
        }.apply()
    }

    fun saveYandex(owner: String, devices: List<YandexDevice>) {
        val json = JSONObject().put("at", System.currentTimeMillis()).put("devices", JSONArray(devices.map { device ->
            JSONObject().put("id", device.id).put("name", device.name).put("location", device.location)
                .put("readings", JSONArray(device.readings.map {
                    JSONObject().put("metric", it.metric.name).put("value", it.value ?: JSONObject.NULL)
                        .put("unit", it.unit).put("at", it.updatedAt)
                }))
        }))
        prefs.edit().putString("yandexOwner", owner).putString("yandexSnapshot", json.toString())
            .remove("yandexFailedOwner").apply()
    }

    fun yandex(owner: String): DashboardSnapshot? = runCatching {
        val values = prefs.all
        if (values["yandexOwner"] != owner) return null
        val json = JSONObject(values["yandexSnapshot"] as? String ?: return null)
        DashboardSnapshot(json.getJSONArray("devices").objects().map { device ->
            YandexDevice(
                device.optString("id").ifEmpty { null },
                device.optString("name").ifEmpty { null },
                device.optString("location"),
                device.getJSONArray("readings").objects().map { reading ->
                    YandexReading(
                        YandexMetric.valueOf(reading.getString("metric")),
                        reading.opt("value")?.takeUnless { it == JSONObject.NULL },
                        reading.optString("unit").ifEmpty { null },
                        reading.optLong("at").takeIf { it > 0 }
                    )
                }
            )
        }, json.getLong("at"))
    }.getOrNull()

    fun yandexFailed(owner: String) = prefs.getString("yandexFailedOwner", null) == owner

    fun markYandexFailed(owner: String) {
        prefs.edit().putString("yandexFailedOwner", owner).apply()
    }

    fun clearYandex() {
        prefs.edit().remove("yandexOwner").remove("yandexSnapshot").remove("yandexFailedOwner")
            .remove("alert.yandex.owner").remove("alert.yandex.active").apply()
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
