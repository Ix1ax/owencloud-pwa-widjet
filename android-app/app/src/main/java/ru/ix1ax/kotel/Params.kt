package ru.ix1ax.kotel

/** Коды параметров прибора в OwenCloud (из таблицы проекта). */
object P {
    const val TEMP = "P512"      // Temp_E — температура теплоносителя
    const val RESET = "P527"     // Reset_E — удалённый сброс
    const val WORKING = "P518"   // Working_E — признак работы горелки
    const val MODE = "P514"      // Mode_E — 0 авто, 1 ручной
    const val SELECT = "P517"    // set_select_E — 0 уставка 1, 1 уставка 2
    const val SP1 = "P529"       // set_point1_E — уставка 1
    const val SP2 = "P531"       // set_point2_E — уставка 2
    const val START = "P516"     // start_E — удалённый старт

    /** Ошибки: если хоть одна не 0 — горит красная лампа «Авария». */
    val ERRORS = linkedMapOf(
        "P520" to "Ошибка откачки",
        "P521" to "Мало масла",
        "P522" to "Нет давления воздуха",
        "P523" to "Не разожглась",
        "P524" to "Ошибка термодатчика",
        "P525" to "Перегрев",
        "P528" to "Выбег горелки",
        "P515" to "Перелив масла",
    )

    val ALL: List<String> = listOf(TEMP, RESET, WORKING, MODE, SELECT, SP1, SP2, START) + ERRORS.keys

    fun num(v: String?): Double? = v?.replace(',', '.')?.trim()?.toDoubleOrNull()
    fun isOn(v: String?): Boolean = (num(v) ?: 0.0) != 0.0
    fun same(a: String?, b: String?): Boolean {
        val x = num(a); val y = num(b)
        return if (x != null && y != null) kotlin.math.abs(x - y) < 0.001 else a == b
    }
}

/** Состояние прибора, собранное из последних значений. */
class State(private val v: Map<String, String>) {
    val temp: String? get() = v[P.TEMP]
    val reset: String? get() = v[P.RESET]
    val working: Boolean get() = P.isOn(v[P.WORKING])
    val hasWorking: Boolean get() = v.containsKey(P.WORKING)
    val activeErrors: List<String> get() = P.ERRORS.filter { P.isOn(v[it.key]) }.map { it.value }
    val alarm: Boolean get() = activeErrors.isNotEmpty()
    operator fun get(code: String): String? = v[code]
}
