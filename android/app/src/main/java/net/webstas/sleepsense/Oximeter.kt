package net.webstas.sleepsense

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.util.UUID

private const val TAG = "Oximeter"
private val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
private val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // notifications from the device
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val RECONNECT_MS = 10_000L
private const val SCAN_MS = 8_000L
private const val WINDOW_MS = 30_000L // one saved sample per window: the lowest SpO2 and the average pulse
private const val LOW_SPO2 = 90
private const val KEEP_MS = 35L * 24 * 3600 * 1000

/** One reading from the finger oximeter: SpO2 in % and pulse in bpm. */
data class OximeterReading(val spo2: Int, val pulse: Int)

/**
 * The Wellue/Viatom PC-60FW family talks over the Nordic UART service. A frame is
 * `AA 55 <type> <length> <function> <payload...> <crc>`, where the length counts everything after
 * itself and the CRC is CRC-8/MAXIM over the whole frame (so a good frame checks to zero).
 * Function 0x01 carries SpO2 and pulse; 0x02 is the pulse waveform, which is not used here.
 * Protocol from https://github.com/sza2/viatom_pc60fw.
 */
class OximeterParser {
    private var buffer = ByteArray(0)

    /** Takes bytes as they arrive and returns the readings completed by them. */
    fun feed(data: ByteArray): List<OximeterReading> {
        buffer += data
        val out = ArrayList<OximeterReading>()
        while (true) {
            val sync = syncIndex()
            if (sync < 0) {
                buffer = buffer.takeLast(1).toByteArray() // keep a lone 0xAA that may start the next frame
                break
            }
            if (sync > 0) buffer = buffer.copyOfRange(sync, buffer.size)
            if (buffer.size < 4) break
            val total = 4 + (buffer[3].toInt() and 0xFF)
            if (buffer.size < total) break
            val frame = buffer.copyOfRange(0, total)
            buffer = buffer.copyOfRange(total, buffer.size)
            if (crc8Maxim(frame) != 0) continue
            readingOf(frame)?.let(out::add)
        }
        return out
    }

    private fun syncIndex(): Int {
        for (i in 0 until buffer.size - 1) {
            if (buffer[i] == 0xAA.toByte() && buffer[i + 1] == 0x55.toByte()) return i
        }
        return -1
    }

    private fun readingOf(frame: ByteArray): OximeterReading? {
        if (frame.size < 7 || frame[4] != 0x01.toByte()) return null
        val spo2 = frame[5].toInt() and 0xFF
        val pulse = frame[6].toInt() and 0xFF
        // 0 means "no finger" or still measuring
        return if (spo2 in 1..100 && pulse in 1..250) OximeterReading(spo2, pulse) else null
    }

    companion object {
        fun crc8Maxim(data: ByteArray): Int {
            var crc = 0
            for (b in data) {
                crc = crc xor (b.toInt() and 0xFF)
                repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C else crc ushr 1 }
            }
            return crc
        }
    }
}

data class OximeterSummary(val average: Int, val lowest: Int, val minutesLow: Int)

/** Settings, the chosen device, and the saved samples. */
object OximeterLog {
    private const val PREFS = "oximeter"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Off until the user switches it on and picks a device
    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_ENABLED, on) }

    fun address(context: Context): String? = prefs(context).getString(KEY_ADDRESS, null)
    fun name(context: Context): String? = prefs(context).getString(KEY_NAME, null)
    fun setDevice(context: Context, address: String, name: String?) =
        prefs(context).edit { putString(KEY_ADDRESS, address); putString(KEY_NAME, name) }

    private fun file(context: Context) = File(context.filesDir, "oximeter.csv")

    /**
     * Lines are "S,<startMillis>" when a run begins and "<millis>,<lowestSpo2>,<averagePulse>" for a
     * sample. Starting a run also drops runs older than [KEEP_MS].
     */
    @Synchronized
    fun startRun(context: Context, now: Long = System.currentTimeMillis()) {
        val f = file(context)
        val cutoff = now - KEEP_MS
        val kept = if (f.exists()) {
            f.readLines().dropWhile { !it.startsWith("S,") || it.substringAfter(',').toLong() < cutoff }
        } else {
            emptyList()
        }
        f.writeText(kept.joinToString("") { it + "\n" } + "S,$now\n")
    }

    @Synchronized
    fun addSample(context: Context, at: Long, spo2: Int, pulse: Int) {
        file(context).appendText("$at,$spo2,$pulse\n")
    }

    /** How the latest run went, or null when it has no samples. */
    @Synchronized
    fun lastRun(context: Context): OximeterSummary? {
        val f = file(context)
        if (!f.exists()) return null
        val spo2 = f.readLines().takeLastWhile { !it.startsWith("S,") }.mapNotNull { line ->
            line.split(',').takeIf { it.size == 3 }?.get(1)?.toIntOrNull()
        }
        if (spo2.isEmpty()) return null
        return OximeterSummary(
            average = spo2.average().toInt(),
            lowest = spo2.min(),
            minutesLow = (spo2.count { it < LOW_SPO2 } * WINDOW_MS / 60_000).toInt(),
        )
    }
}

/** The Bluetooth permissions a connection or a scan needs on this Android version. */
fun oximeterPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) // needed to scan before Android 12
    }

fun oximeterPermissionsGranted(context: Context) = oximeterPermissions().all {
    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
}

/** Keeps a connection to the chosen oximeter while the watch tracks, and saves what it measures. */
@SuppressLint("MissingPermission") // start() checks oximeterPermissionsGranted() first
class OximeterMonitor(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var parser = OximeterParser()

    private var windowStart = 0L
    private var windowMin = 101
    private var pulseSum = 0
    private var pulseCount = 0

    fun start() {
        val address = OximeterLog.address(context) ?: return
        if (running || !oximeterPermissionsGranted(context)) return
        running = true
        OximeterLog.startRun(context)
        connect(address)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        flushWindow()
        gatt?.close()
        gatt = null
    }

    private fun connect(address: String) {
        if (!running) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            handler.postDelayed({ connect(address) }, RECONNECT_MS)
            return
        }
        gatt?.close()
        parser = OximeterParser()
        gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else {
                g.close()
                if (gatt === g) gatt = null
                // The oximeter switches itself off when the finger comes out: keep trying until stopped
                OximeterLog.address(context)?.let { handler.postDelayed({ connect(it) }, RECONNECT_MS) }
            }
        }

        @Suppress("DEPRECATION") // the replacement needs Android 13; minSdk is 28
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val tx = g.getService(NUS_SERVICE)?.getCharacteristic(NUS_TX)
            if (tx == null) {
                Log.w(TAG, "no Nordic UART service on ${g.device.address}, it has: ${g.services.map { it.uuid }}")
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(tx, true)
            tx.getDescriptor(CCCD)?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(it)
            }
            Log.i(TAG, "connected to ${g.device.address}")
        }

        @Deprecated("Called below Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") onData(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onData(value)
        }
    }

    private fun onData(data: ByteArray) {
        handler.post {
            if (!running) return@post
            val now = System.currentTimeMillis()
            Log.v(TAG, "rx " + data.joinToString(" ") { "%02x".format(it) })
            for (r in parser.feed(data)) {
                if (windowStart == 0L) windowStart = now
                windowMin = minOf(windowMin, r.spo2)
                pulseSum += r.pulse
                pulseCount++
            }
            if (windowStart != 0L && now - windowStart >= WINDOW_MS) flushWindow()
        }
    }

    private fun flushWindow() {
        if (pulseCount > 0) OximeterLog.addSample(context, windowStart, windowMin, pulseSum / pulseCount)
        windowStart = 0L
        windowMin = 101
        pulseSum = 0
        pulseCount = 0
    }
}

/** A short scan for nearby devices, so the user can pick their oximeter. */
@SuppressLint("MissingPermission") // the caller checks oximeterPermissionsGranted() first
object OximeterScanner {
    data class Found(val address: String, val name: String)

    /** Calls [onDone] on the main thread after a few seconds with the named devices seen, strongest first. */
    fun scan(context: Context, onDone: (List<Found>) -> Unit) {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            onDone(emptyList())
            return
        }
        val seen = LinkedHashMap<String, Pair<String, Int>>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: return
                seen[result.device.address] = name to result.rssi
            }
        }
        scanner.startScan(cb)
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { scanner.stopScan(cb) }
            onDone(seen.entries.sortedByDescending { it.value.second }.map { Found(it.key, it.value.first) })
        }, SCAN_MS)
    }
}
