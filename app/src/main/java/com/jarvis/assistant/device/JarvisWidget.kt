package com.jarvis.assistant.device

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R

/**
 * The home-screen widget.
 *
 * One small HUD plate: a live 12-hour clock, the wordmark, and a line that says what it does. Tapping
 * anywhere opens JARVIS with the voice surface armed. A `TextClock` keeps the time correct without
 * waking the app, so the widget costs nothing between refreshes.
 *
 * Deliberately no torch toggle: a remote-views button that cannot report the real torch state would
 * be a lie, and the Quick Settings tile already covers that properly.
 */
class JarvisWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            try {
                appWidgetManager.updateAppWidget(id, build(context))
            } catch (t: Throwable) {
                // A launcher that rejects the layout simply keeps the previous one.
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        refresh(context)
    }

    companion object {
        fun build(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_jarvis)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(Bubble.EXTRA_VOICE, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            return views
        }

        /** Repaints every placed widget. Called from the app when settings change. */
        fun refresh(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, JarvisWidget::class.java))
                ids.forEach { id -> manager.updateAppWidget(id, build(context)) }
            } catch (t: Throwable) {
                // No widgets placed, or the launcher is refusing updates.
            }
        }
    }
}
