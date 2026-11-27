package ru.ix1ax.kotel

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.Window
import android.widget.TextView

internal fun Activity.confirmCommand(
    title: String, detail: String, action: String = "Подтвердить",
    onCancel: () -> Unit = {}, run: () -> Unit
) {
    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(R.layout.command_dialog)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.findViewById<TextView>(R.id.command_title).text = title
    dialog.findViewById<TextView>(R.id.command_detail).apply {
        text = detail
        visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
    }
    dialog.findViewById<TextView>(R.id.command_apply).apply {
        text = action
        setOnClickListener { dialog.dismiss(); run() }
    }
    dialog.findViewById<View>(R.id.command_cancel).setOnClickListener { dialog.cancel() }
    dialog.setOnCancelListener { onCancel() }
    dialog.show()
    dialog.window?.setLayout((resources.displayMetrics.widthPixels * .88f).toInt()
        .coerceAtMost((420 * resources.displayMetrics.density).toInt()), -2)
}
