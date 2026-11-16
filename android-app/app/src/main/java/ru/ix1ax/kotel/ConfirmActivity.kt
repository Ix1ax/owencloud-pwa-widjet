package ru.ix1ax.kotel

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle

/** Маленькое окошко «Записать Сброс = 1?» поверх рабочего стола (для кнопки в виджете). */
class ConfirmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent.getStringExtra(KotelWidget.EXTRA_CODE)
        val value = intent.getStringExtra(KotelWidget.EXTRA_VALUE)
        val label = intent.getStringExtra(KotelWidget.EXTRA_LABEL) ?: code
        if (code == null || value == null) { finish(); return }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("$label → $value?")
            .setMessage(Prefs(this).deviceName)
            .setPositiveButton("Записать") { _, _ ->
                sendBroadcast(
                    Intent(this, KotelWidget::class.java)
                        .setAction(KotelWidget.ACTION_WRITE)
                        .putExtra(KotelWidget.EXTRA_CODE, code)
                        .putExtra(KotelWidget.EXTRA_VALUE, value)
                        .putExtra(KotelWidget.EXTRA_LABEL, label)
                )
                finish()
            }
            .setNegativeButton("Отмена") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
