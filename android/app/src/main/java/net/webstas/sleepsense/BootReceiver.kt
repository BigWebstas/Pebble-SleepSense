package net.webstas.sleepsense

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the alarm bridge after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, AlarmBridgeService::class.java))
        }
    }
}
