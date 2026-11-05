package net.webstas.sleepsense

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "Backup"

/** Where backups go and when they are made on their own. */
object BackupPrefs {
    private const val PREFS = "backup"
    private const val KEY_FOLDER = "folder"
    private const val KEY_ON_START = "on_start"
    private const val KEY_ON_STOP = "on_stop"
    private const val KEY_LAST = "last"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? = prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse)

    fun setFolder(context: Context, uri: Uri) = prefs(context).edit { putString(KEY_FOLDER, uri.toString()) }

    fun onStart(context: Context) = prefs(context).getBoolean(KEY_ON_START, false)

    fun setOnStart(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_ON_START, on) }

    fun onStop(context: Context) = prefs(context).getBoolean(KEY_ON_STOP, false)

    fun setOnStop(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_ON_STOP, on) }

    /** When the last backup was made (0 if never), and the file it made. */
    fun last(context: Context): Pair<Long, String>? =
        prefs(context).getString(KEY_LAST, null)?.split('|', limit = 2)?.takeIf { it.size == 2 }
            ?.let { (it[0].toLongOrNull() ?: return null) to it[1] }

    fun setLast(context: Context, at: Long, name: String) = prefs(context).edit { putString(KEY_LAST, "$at|$name") }
}

/**
 * A backup is one zip with the app's settings (every preference file but the transient ones) and its
 * data files (sleep history, oximeter and chest logs; not the noise clips, which can be large). The
 * Spotify link and the sleep sound files are only references and may need choosing again on a new phone.
 * Settings include the Home Assistant and webhook details, so keep the folder private.
 */
object Backup {
    private const val AUTO_PREFIX = "sleepsense-auto-"
    private const val MANUAL_PREFIX = "sleepsense-backup-"
    private const val KEEP_AUTO = 10
    private const val AFTER_STOP_MS = 30_000L // the finished night's history is pushed shortly after the stop

    // State of the moment, not settings; and this screen's own choice of folder stays where it is
    private val SKIP_PREFS = setOf("widget_state", "update", "sleep_state", "backup")
    private val DATA_FILES = listOf("sessions.json", "oximeter.csv", "oximeter_wave.csv", "oximeter_events.csv", "chest_rate.csv")

    /** Called when the watch starts or stops tracking, to back up if that was asked for. */
    fun onTracking(context: Context, started: Boolean) {
        val app = context.applicationContext
        if (BackupPrefs.folder(app) == null) return
        if (started && BackupPrefs.onStart(app)) {
            CoroutineScope(Dispatchers.IO).launch { runCatching { write(app, auto = true) }.onFailure { Log.w(TAG, "backup failed: $it") } }
        } else if (!started && BackupPrefs.onStop(app)) {
            CoroutineScope(Dispatchers.IO).launch {
                delay(AFTER_STOP_MS)
                runCatching { write(app, auto = true) }.onFailure { Log.w(TAG, "backup failed: $it") }
            }
        }
    }

    /** Writes a backup into the chosen folder and returns its file name; throws if that is not possible. */
    fun write(context: Context, auto: Boolean = false): String {
        val tree = BackupPrefs.folder(context) ?: error("no backup folder")
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = (if (auto) AUTO_PREFIX else MANUAL_PREFIX) + stamp + ".zip"
        val doc = DocumentsContract.createDocument(context.contentResolver, folder, "application/zip", name)
            ?: error("could not create $name")
        try {
            context.contentResolver.openOutputStream(doc)!!.use { write(context, it) }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc) } // no half-written backup left behind
            throw e
        }
        BackupPrefs.setLast(context, System.currentTimeMillis(), name)
        if (auto) pruneAuto(context, tree)
        return name
    }

    private fun pruneAuto(context: Context, tree: Uri) {
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val found = mutableListOf<Pair<String, String>>() // file name to document id
            context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) if (c.getString(1).startsWith(AUTO_PREFIX)) found += c.getString(1) to c.getString(0)
            }
            // The names carry the time, so the oldest sort first
            found.sortedBy { it.first }.dropLast(KEEP_AUTO).forEach { (_, id) ->
                DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not tidy old backups: $e")
        }
    }

    fun write(context: Context, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            dir.listFiles { f -> f.name.endsWith(".xml") }?.map { it.name.removeSuffix(".xml") }?.sorted().orEmpty()
                .filter { it !in SKIP_PREFS }.forEach { name ->
                    zip.putNextEntry(ZipEntry("prefs/$name.json"))
                    zip.write(prefsToJson(context, name).toString().toByteArray())
                    zip.closeEntry()
                }
            DATA_FILES.map { File(context.filesDir, it) }.filter { it.exists() }.forEach { file ->
                zip.putNextEntry(ZipEntry("files/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun prefsToJson(context: Context, name: String): JSONObject {
        val json = JSONObject()
        for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
            val (type, v) = when (value) {
                is Boolean -> "b" to value
                is Int -> "i" to value
                is Long -> "l" to value
                is Float -> "f" to value.toDouble()
                is String -> "s" to value
                is Set<*> -> "set" to JSONArray(value.map { it.toString() })
                else -> continue
            }
            json.put(key, JSONObject().put("t", type).put("v", v))
        }
        return json
    }

    /** Puts a backup back: its settings replace the ones here, its data files replace the files. Returns how many items were restored. */
    fun restore(context: Context, input: InputStream): Int {
        // Read it all first, so a damaged file changes nothing
        val prefs = mutableMapOf<String, JSONObject>()
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val parts = entry.name.split('/')
                if (entry.isDirectory || parts.size != 2) return@forEach
                val name = parts[1]
                when {
                    parts[0] == "prefs" && name.endsWith(".json") -> {
                        val pref = name.removeSuffix(".json")
                        if (pref !in SKIP_PREFS && pref.all { it.isLetterOrDigit() || it == '_' }) {
                            prefs[pref] = JSONObject(zip.readBytes().decodeToString())
                        }
                    }
                    parts[0] == "files" && name in DATA_FILES -> files[name] = zip.readBytes()
                }
            }
        }
        require(prefs.isNotEmpty() || files.isNotEmpty()) { "not a SleepSense backup" }

        for ((name, json) in prefs) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) {
                clear()
                for (key in json.keys()) {
                    val item = json.getJSONObject(key)
                    when (item.getString("t")) {
                        "b" -> putBoolean(key, item.getBoolean("v"))
                        "i" -> putInt(key, item.getInt("v"))
                        "l" -> putLong(key, item.getLong("v"))
                        "f" -> putFloat(key, item.getDouble("v").toFloat())
                        "s" -> putString(key, item.getString("v"))
                        "set" -> item.getJSONArray("v").let { a -> putStringSet(key, (0 until a.length()).map { a.getString(it) }.toSet()) }
                    }
                }
            }
        }
        for ((name, bytes) in files) {
            val tmp = File(context.filesDir, "$name.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(File(context.filesDir, name))
        }
        return prefs.size + files.size
    }

    /** Keeps access to a folder chosen with the system picker across restarts. */
    fun keepAccess(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}
