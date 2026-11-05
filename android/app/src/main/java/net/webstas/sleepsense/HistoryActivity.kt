package net.webstas.sleepsense

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Sleep history graphs and the Markdown export. The page is the settings-page code from the
 * watchapp (report-core.js, copied into assets at build time) running in a WebView, so the graphs
 * and the export are identical in both places.
 */
class HistoryActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var pendingText: String? = null

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            val text = pendingText
            pendingText = null
            val ok = uri != null && text != null && runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
            }.getOrDefault(false)
            web.evaluateJavascript("window.onSaved($ok)", null)
        }

    // Called from the page's JavaScript on a background thread
    private inner class Bridge {
        @JavascriptInterface fun sessions(): String = SessionStore.loadWithOximeter(this@HistoryActivity)

        @JavascriptInterface fun lastExport(): Long = SessionStore.lastExport(this@HistoryActivity)

        @JavascriptInterface fun markExported(at: Long) = SessionStore.markExported(this@HistoryActivity, at)

        // Asks first; the page hears the answer through window.onDeleted
        @JavascriptInterface fun deleteSession(start: Long, label: String) {
            runOnUiThread {
                MaterialAlertDialogBuilder(this@HistoryActivity)
                    .setTitle(R.string.delete_session_title)
                    .setMessage(getString(R.string.delete_session_message, label))
                    .setPositiveButton(R.string.delete_session_confirm) { _, _ ->
                        SessionStore.delete(this@HistoryActivity, start)
                        web.evaluateJavascript("window.onDeleted($start)", null)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        // Stars are saved at once; the page already shows them
        @JavascriptInterface fun rateSession(start: Long, stars: Int) =
            SessionStore.setRating(this@HistoryActivity, start, stars.coerceIn(0, 5))

        // The AI summary of a session: its text ("" if none), whether one can be asked for, and asking for it.
        // The page hears the answer through window.onSummary
        @JavascriptInterface fun summary(start: Long): String = SessionSummaries.get(this@HistoryActivity, start)?.first ?: ""

        @JavascriptInterface fun canSummarize(): Boolean =
            CoachPrefs.isEnabled(this@HistoryActivity) && CoachPrefs.apiKey(this@HistoryActivity) != null

        @JavascriptInterface fun summarize(start: Long) {
            Thread {
                val session = SleepCoach.finishedSessions(this@HistoryActivity).firstOrNull { it.optLong("start") == start }
                val outcome = runCatching {
                    val s = session ?: throw CoachException(getString(R.string.coach_no_data))
                    SleepCoach.summarizeSession(this@HistoryActivity, s).also { SessionSummaries.put(this@HistoryActivity, start, it) }
                }
                val text = outcome.getOrNull() ?: ((outcome.exceptionOrNull() as? CoachException)?.message ?: getString(R.string.coach_failed, outcome.exceptionOrNull()?.message ?: ""))
                runOnUiThread { web.evaluateJavascript("window.onSummary($start, ${outcome.isSuccess}, ${org.json.JSONObject.quote(text)})", null) }
            }.start()
        }

        @JavascriptInterface fun copy(text: String) {
            runOnUiThread {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("SleepSense export", text))
            }
        }

        // Opens the Android share sheet with the export as plain text (what Copy puts on the clipboard)
        @JavascriptInterface fun share(text: String) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "SleepSense export")
            }
            runOnUiThread { startActivity(Intent.createChooser(send, "Share sleep export")) }
        }

        @JavascriptInterface fun save(filename: String, text: String) {
            runOnUiThread {
                pendingText = text
                createDocument.launch(filename)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT) // the theme's night sky shows until the page is up
            // Only our own bundled page is ever loaded here
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true
            }
            addJavascriptInterface(Bridge(), "Android")
        }
        setContentView(FrameLayout(this).apply {
            addView(web)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })

        val core = assets.open("report-core.js").bufferedReader().use { it.readText() }
        val page = assets.open("history.html").bufferedReader().use { it.readText() }
            .replace("<!--REPORT_CORE-->", "<script>var module = {};\n$core</script>")
        web.loadDataWithBaseURL("https://sleepsense.local/", page, "text/html", "utf-8", null)
    }
}
