package net.webstas.sleepsense

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews

/**
 * Small home-screen widget: logo, tracking status and the next alarm. Tapping it starts sleep
 * tracking on the watch (opening the watch app first if needed); while already tracking, a tap
 * just opens this app, so a stray tap can't do anything to a running night.
 */
class SleepWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TAP) tap(context)
    }

    private fun tap(context: Context) {
        Log.i(TAG, "tap: watchAppOpen=${WidgetState.watchAppOpen(context)} tracking=${WidgetState.tracking(context)}")
        if (WidgetState.tracking(context)) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val result = goAsync()
        TrackingControl.start(context) { result.finish() }
    }

    companion object {
        private const val TAG = "SleepWidget"
        const val ACTION_TAP = "net.webstas.sleepsense.WIDGET_TAP"

        private fun views(context: Context): RemoteViews {
            val (title, subtitle) = WidgetState.lines(context)
            val tap = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, SleepWidgetProvider::class.java).setAction(ACTION_TAP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return RemoteViews(context.packageName, R.layout.widget_status).apply {
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_subtitle, subtitle)
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
        }

        /** Redraws every placed widget (status changed, alarm changed, widget tapped). */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SleepWidgetProvider::class.java))
            if (ids.isNotEmpty()) ids.forEach { manager.updateAppWidget(it, views(context)) }
        }
    }
}
