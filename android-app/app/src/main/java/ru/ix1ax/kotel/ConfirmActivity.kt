package ru.ix1ax.kotel

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle

/** Маленькое окошко «Записать Reset = 1?» поверх рабочего стола. */
class ConfirmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val value = intent.getStringExtra(KotelWidget.EXTRA_VALUE)
        if (value == null) { finish(); return }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Записать Reset = $value?")
            .setMessage(Prefs(this).deviceName)
            .setPositiveButton("Записать") { _, _ ->
                sendBroadcast(
                    Intent(this, KotelWidget::class.java)
                        .setAction(KotelWidget.ACTION_WRITE)
                        .putExtra(KotelWidget.EXTRA_VALUE, value)
                )
                finish()
            }
            .setNegativeButton("Отмена") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
