package net.webstas.sleepsense

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "SleepListenerService"

/** Receives SleepSense status updates from the watch and writes finished sessions to Health Connect. */
class SleepListenerService : BasePebbleListenerService() {
    private lateinit var sender: DefaultPebbleSender

    // Only delivered while this service is alive (the system does not wake apps for it), so the
    // watch app opening and its status messages also trigger a sync.
    private val alarmChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            coroutineScope.launch { PhoneAlarmSync.sync(this@SleepListenerService, sender, null, null) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sender = DefaultPebbleSender(this)
        ContextCompat.registerReceiver(
            this, alarmChanged, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != WatchProtocol.APP_UUID) return
        coroutineScope.launch { PhoneAlarmSync.sync(this@SleepListenerService, sender, null, null, listOf(watch)) }
    }

    override fun onDestroy() {
        unregisterReceiver(alarmChanged)
        sender.close()
        super.onDestroy()
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != WatchProtocol.APP_UUID) return ReceiveResult.Nack

        val tracking = data[WatchProtocol.KEY_TRACKING_ACTIVE]?.let(::intValueOf)
        if (tracking == null) return ReceiveResult.Ack // e.g. a voice note, nothing for us

        // The watch reports its alarm time with every status; resync if it differs from the phone's
        val watchHour = data[WatchProtocol.KEY_ALARM_TARGET_HOUR]?.let(::intValueOf)
        val watchMin = data[WatchProtocol.KEY_ALARM_TARGET_MIN]?.let(::intValueOf)
        if (watchHour != null && watchMin != null) PhoneAlarmSync.noteWatchAlarm(this, watchHour, watchMin)
        PhoneAlarmSync.sync(this, sender, watchHour, watchMin, listOf(watch))

        val stage = data[WatchProtocol.KEY_STATUS_STATE]?.let(::intValueOf)
        val heartRate = data[WatchProtocol.KEY_STATUS_HEART_RATE]?.let(::intValueOf)
        SleepRecorder.onWatchUpdate(this, tracking != 0, stage, heartRate)
        if (tracking == 0) {
            val left = SleepRecorder.flush(this)
            Log.i(TAG, "Session ended; $left queued for Health Connect")
        }
        return ReceiveResult.Ack
    }
}

private fun intValueOf(item: PebbleDictionaryItem): Int? = when (item) {
    is PebbleDictionaryItem.Int8 -> item.value.toInt()
    is PebbleDictionaryItem.Int16 -> item.value.toInt()
    is PebbleDictionaryItem.Int32 -> item.value
    is PebbleDictionaryItem.UInt8 -> item.value.toInt()
    is PebbleDictionaryItem.UInt16 -> item.value.toInt()
    is PebbleDictionaryItem.UInt32 -> item.value.toInt()
    else -> null
}
