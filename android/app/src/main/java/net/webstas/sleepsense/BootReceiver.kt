package net.webstas.sleepsense

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the alarm bridge after a reboot or after the app is updated (an update stops it). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context.startForegroundService(Intent(context, AlarmBridgeService::class.java))
        }
    }
}
