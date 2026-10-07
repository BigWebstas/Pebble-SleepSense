package net.webstas.sleepsense

import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID

private const val TAG = "SleepListenerService"

/** Receives SleepSense status updates from the watch and writes finished sessions to Health Connect. */
class SleepListenerService : BasePebbleListenerService() {

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != WatchProtocol.APP_UUID) return ReceiveResult.Nack

        val tracking = data[WatchProtocol.KEY_TRACKING_ACTIVE]?.let(::intValueOf)
        if (tracking == null) return ReceiveResult.Ack // e.g. a voice note, nothing for us

        val stage = data[WatchProtocol.KEY_STATUS_STATE]?.let(::intValueOf)
        val heartRate = data[WatchProtocol.KEY_STATUS_HEART_RATE]?.let(::intValueOf)
        SleepRecorder.onWatchUpdate(this, tracking != 0, stage, heartRate)
        HomeAssistant.publishState(tracking != 0, stage, heartRate)
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
