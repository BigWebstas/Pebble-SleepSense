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
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sleep history graphs and the Markdown export. The page is the settings-page code from the
 * watchapp (report-core.js, copied into assets at build time) running in a WebView, so the graphs
 * and the export are identical in both places.
 */
class HistoryActivity : ComponentActivity() {
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
        @JavascriptInterface fun sessions(): String = SessionStore.load(this@HistoryActivity)

        @JavascriptInterface fun lastExport(): Long = SessionStore.lastExport(this@HistoryActivity)

        @JavascriptInterface fun markExported(at: Long) = SessionStore.markExported(this@HistoryActivity, at)

        @JavascriptInterface fun copy(text: String) {
            runOnUiThread {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("SleepSense export", text))
            }
        }

        // Opens the Android share sheet with the export attached as a .md file
        @JavascriptInterface fun share(filename: String, text: String) {
            val dir = File(cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val file = File(dir, filename).apply { writeText(text) }
            val uri = FileProvider.getUriForFile(this@HistoryActivity, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SleepSense export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
