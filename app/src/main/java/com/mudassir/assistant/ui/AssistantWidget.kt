package com.mudassir.assistant.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mudassir.assistant.R
import com.mudassir.assistant.actions.PrayerAlerts
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Home screen widget: tap the orb to talk; shows the next prayer time. */
class AssistantWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(ctx)) }
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

        fun views(ctx: Context): RemoteViews {
            val listen = PendingIntent.getActivity(
                ctx, 6100,
                Intent(ctx, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_LISTEN, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val (name, at) = PrayerAlerts.next(ctx)
            val day = if (at.toLocalDate() == LocalDate.now()) "" else " tomorrow"
            return RemoteViews(ctx.packageName, R.layout.widget_assistant).apply {
                setTextViewText(R.id.widgetPrayer, "$name at ${at.format(TIME)}$day")
                setOnClickPendingIntent(R.id.widgetRoot, listen)
            }
        }

        /** Redraw all widgets (after a prayer time passes, or when the location changes). */
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, AssistantWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(ctx))
        }
    }
}
