package ru.ix1ax.kotel

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.util.UUID

class DashboardWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, it) }
        DashboardMonitor.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        DashboardStore(context).delete(ids)
    }

    override fun onDisabled(context: Context) = DashboardMonitor.schedule(context)

    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        DashboardStore(context).restore(oldIds, newIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            PINNED -> {
                val id = widgetId(intent)
                val config = DashboardConfig.decode(intent.getStringExtra(EXTRA_CONFIG)) ?: return
                if (!isDashboard(context, id)) return
                DashboardStore(context).apply {
                    save(id, config)
                    clearDraft(config)
                }
                render(context, id)
                DashboardMonitor.schedule(context)
                DashboardMonitor.requestRefresh(context)
            }
            REFRESH -> if (isDashboard(context, widgetId(intent))) DashboardMonitor.requestRefresh(context)
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED -> {
                renderAll(context)
                DashboardMonitor.schedule(context)
            }
        }
    }

    companion object {
        private const val PINNED = "ru.ix1ax.kotel.DASHBOARD_PINNED"
        private const val REFRESH = "ru.ix1ax.kotel.DASHBOARD_REFRESH"
        private const val EXTRA_CONFIG = "dashboardConfig"
        internal const val EXTRA_TARGET = "dashboardTarget"
        internal const val EXTRA_TILE_INDEX = "dashboardTileIndex"
        internal const val EXTRA_TILE_KIND = "dashboardTileKind"
        internal const val EXTRA_DEVICE = "dashboardDevice"

        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(component(context))

        private fun component(context: Context) = ComponentName(context, DashboardWidget::class.java)

        fun isDashboard(context: Context, id: Int): Boolean =
            AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider == component(context)

        fun widgetId(intent: Intent) = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )

        fun renderAll(context: Context) = ids(context).forEach { render(context, it) }

        @Suppress("DEPRECATION")
        fun notifyDataChanged(context: Context) {
            val ids = ids(context)
            if (ids.isNotEmpty()) AppWidgetManager.getInstance(context)
                .notifyAppWidgetViewDataChanged(ids, R.id.dashboard_widget_cards)
        }

        private val mutableFlag get() = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

        internal fun pin(context: Context, config: DashboardConfig): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            if (!manager.isRequestPinAppWidgetSupported) return false
            val callback = PendingIntent.getBroadcast(context, 0,
                Intent(context, DashboardWidget::class.java).setAction(PINNED)
                    .setData(Uri.parse("dashboard://pin/${UUID.randomUUID()}"))
                    .putExtra(EXTRA_CONFIG, config.encode()),
                mutableFlag or PendingIntent.FLAG_ONE_SHOT)
            if (manager.requestPinAppWidget(component(context), null, callback)) return true
            callback.cancel()
            return false
        }

        @Suppress("DEPRECATION")
        fun render(context: Context, id: Int) {
            val manager = AppWidgetManager.getInstance(context)
            val config = DashboardStore(context).config(id)
            val grid = config?.grid == true
            val remote = RemoteViews(context.packageName, if (grid) R.layout.widget_dashboard_grid else R.layout.widget_dashboard_list)
            remote.setTextViewText(R.id.dashboard_widget_title, config?.title ?: context.getString(R.string.dashboard_widget_name))
            val edit = PendingIntent.getActivity(context, id,
                Intent(context, DashboardBuilderActivity::class.java)
                    .setData(Uri.parse("dashboard://edit/$id")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            remote.setOnClickPendingIntent(R.id.dashboard_widget_edit, edit)
            remote.setOnClickPendingIntent(R.id.dashboard_widget_empty, edit)
            remote.setOnClickPendingIntent(R.id.dashboard_widget_refresh, PendingIntent.getBroadcast(context, id,
                Intent(context, DashboardWidget::class.java).setAction(REFRESH).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val adapter = Intent(context, DashboardRemoteViewsService::class.java)
                .setData(Uri.parse("dashboard://cards/$id/$grid")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            remote.setRemoteAdapter(R.id.dashboard_widget_cards, adapter)
            remote.setEmptyView(R.id.dashboard_widget_cards, R.id.dashboard_widget_empty)
            remote.setPendingIntentTemplate(R.id.dashboard_widget_cards, PendingIntent.getActivity(context, id,
                Intent(context, DashboardActionActivity::class.java)
                    .setData(Uri.parse("dashboard://action/$id")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                mutableFlag or PendingIntent.FLAG_UPDATE_CURRENT))
            manager.updateAppWidget(id, remote)
            manager.notifyAppWidgetViewDataChanged(id, R.id.dashboard_widget_cards)
        }

        internal fun item(context: Context, tile: DashboardTile, card: DashboardCard, position: Int): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_dashboard_card).apply {
                setTextViewText(R.id.dashboard_card_title, card.title)
                setTextViewText(R.id.dashboard_card_value, card.value)
                setViewVisibility(R.id.dashboard_card_action,
                    if (tile.owen?.parameter != null || tile.kind == DashboardTile.YANDEX_POWER) View.VISIBLE else View.GONE)
                if (card.alert) setTextColor(R.id.dashboard_card_value, context.getColor(R.color.red))
                val fill = Intent().putExtra(EXTRA_TILE_INDEX, position)
                    .putExtra(EXTRA_TILE_KIND, tile.kind).putExtra(EXTRA_DEVICE, tile.deviceId)
                setOnClickFillInIntent(R.id.dashboard_card_root, fill)
                val controls = tile.owen?.parameter != null || tile.kind == DashboardTile.YANDEX_POWER
                setViewVisibility(R.id.dashboard_card_controls, if (controls) View.VISIBLE else View.GONE)
                if (controls) {
                    val reset = tile.owen == OwenTile.RESET
                    setTextViewText(R.id.dashboard_card_off, if (reset) "Сброс 0" else "Выкл.")
                    setTextViewText(R.id.dashboard_card_on, if (reset) "Сброс 1" else "Вкл.")
                    setOnClickFillInIntent(R.id.dashboard_card_off, Intent(fill).putExtra(EXTRA_TARGET, 0))
                    setOnClickFillInIntent(R.id.dashboard_card_on, Intent(fill).putExtra(EXTRA_TARGET, 1))
                }
            }
    }
}

class DashboardRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = DashboardCards(applicationContext, DashboardWidget.widgetId(intent))
}

private class DashboardCards(private val context: Context, private val id: Int) : RemoteViewsService.RemoteViewsFactory {
    private var cards: List<Pair<DashboardTile, DashboardCard>> = emptyList()

    override fun onCreate() = Unit
    override fun onDataSetChanged() {
        val config = DashboardStore(context).config(id)
        val data = DashboardData(context)
        cards = config?.tiles.orEmpty().map { Pair(it, data.card(it)) }
    }
    override fun getCount() = cards.size
    override fun getViewAt(position: Int) = cards.getOrNull(position)?.let { (tile, card) ->
        DashboardWidget.item(context, tile, card, position)
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
    override fun onDestroy() { cards = emptyList() }
}
