package net.webstas.sleepsense

import android.content.Context
import android.content.Intent
import android.util.Log
import io.rebble.pebblekit2.client.DefaultPebbleSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "TrackingControl"

/** Starting and stopping sleep tracking on the watch from the phone (the app's button and the widget). */
object TrackingControl {

    /**
     * Starts tracking: queues the command for the watchapp's JS (collected through the bridge) and
     * asks the Pebble app to open SleepSense on the watch. "The watch app is open" is only inferred
     * from the last check-in and can be minutes stale, so it is always asked to open. [onDone] runs
     * when that request has finished (a widget uses it to end its broadcast).
     */
    fun start(context: Context, onDone: () -> Unit = {}) {
        // The queued command is collected through the bridge service, so make sure it is running
        context.startForegroundService(Intent(context, AlarmBridgeService::class.java))
        Commands.queueStart()
        WidgetState.markStarting(context)
        SleepWidgetProvider.refreshAll(context)
        openWatchApp(context, onDone)
    }

    /** Stops tracking; only possible while the watch app is open (that is when it can hear the request). */
    fun stop(context: Context) {
        context.startForegroundService(Intent(context, AlarmBridgeService::class.java))
        Commands.queueStop()
    }

    // A broadcast receiver's own context may not bind to other apps, so use the application's, and
    // never let a failure here take the whole app (and its background bridge) down.
    private fun openWatchApp(context: Context, onDone: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            var sender: DefaultPebbleSender? = null
            try {
                sender = DefaultPebbleSender(context.applicationContext)
                Log.i(TAG, "open watch app -> " + sender.startAppOnTheWatch(WatchProtocol.APP_UUID, null))
            } catch (e: Exception) {
                Log.w(TAG, "could not open the watch app: $e")
            } finally {
                runCatching { sender?.close() }
                onDone()
            }
        }
    }
}
