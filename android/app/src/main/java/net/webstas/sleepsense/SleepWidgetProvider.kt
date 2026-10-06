package net.webstas.sleepsense

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import io.rebble.pebblekit2.client.DefaultPebbleSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        // The queued command is collected through the bridge service, so make sure it is running
        context.startForegroundService(Intent(context, AlarmBridgeService::class.java))
        Commands.queueStart()
        WidgetState.markStarting(context)
        refreshAll(context)
        // Always ask the Pebble app to open SleepSense: "the watch app is open" is only inferred from
        // the last check-in and can be minutes stale (the app may have just been closed)
        openWatchApp(context)
        Log.i(TAG, "tap: start queued")
    }

    // The Pebble app opens SleepSense on the watch; the app's JS then collects the queued command.
    // A broadcast receiver's own context may not bind to other apps, so use the application's, and
    // never let a failure here take the whole app (and its background bridge) down.
    private fun openWatchApp(context: Context) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            var sender: DefaultPebbleSender? = null
            try {
                sender = DefaultPebbleSender(context.applicationContext)
                Log.i(TAG, "open watch app -> " + sender.startAppOnTheWatch(WatchProtocol.APP_UUID, null))
            } catch (e: Exception) {
                Log.w(TAG, "could not open the watch app: $e")
            } finally {
                runCatching { sender?.close() }
                result.finish()
            }
        }
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
