package com.hermes.android

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 1 shell: shows a status screen while [HermesRuntime] starts, then
 * swaps to a WebView pointed at the local dashboard once it answers. The
 * Phase 1 runtime always fails (see [HermesRuntime]), so today this only
 * ever reaches [showFatalError] — the WebView path is exercised starting
 * Phase 3, once a real runtime is wired in.
 */
class MainActivity : ComponentActivity() {

    private lateinit var container: LinearLayout
    private lateinit var statusView: TextView
    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtime: HermesRuntime by lazy { HermesRuntime.create(this) }

    private val dashboardPollIntervalMs = 500L
    private val dashboardPollTimeoutMs = 60_000L

    // Android's WebView shows no file chooser at all for an <input type="file"> click
    // unless the host app implements onShowFileChooser() below — without it, the
    // dashboard's image/file attachment buttons look decorative (click does nothing).
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            callback?.onReceiveValue(FileChooserParams.parseResult(result.resultCode, result.data))
        }

    // The dashboard's "import folder" button has no WebView equivalent of desktop
    // Chrome's real directory upload (no webkitRelativePath comes back through
    // onShowFileChooser), so it calls window.HermesAndroid.pickFolder() directly
    // instead of clicking a hidden <input webkitdirectory> — see HermesFolderBridge.
    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) handleFolderPicked(treeUri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // Apps targeting Android 15 (API 35) draw edge-to-edge unconditionally —
        // without this, content (the status text, and the dashboard's own composer
        // at the bottom of the WebView) renders underneath the status bar and the
        // navigation/taskbar rather than being pushed clear of them.
        container.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // tappableElement() covers a persistent tablet taskbar dock (reserved
                // screen space a Samsung-style taskbar occupies) in addition to the
                // plain navigation bar; systemBars() alone missed it.
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.tappableElement(),
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }

        statusView = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            textSize = 16f
            setTextColor(Color.BLACK)
            text = getString(R.string.app_name)
        }
        val statusScroll = ScrollView(this).apply {
            addView(
                statusView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        container.addView(
            statusScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        setContentView(container)
        startRuntime()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        runtime.stop()
        super.onDestroy()
    }

    private fun setStatus(message: String) {
        mainHandler.post { statusView.text = message }
    }

    private fun startRuntime() {
        Thread({
            try {
                runtime.start(::setStatus)
                waitForDashboardThenShow(runtime.port)
            } catch (t: Throwable) {
                mainHandler.post { showFatalError(t) }
            }
        }, "hermes-runtime-start").start()
    }

    // faulthandler.dump_traceback_later() fires at 25s (HermesRuntime.start) —
    // give it a couple seconds' margin before treating a stuck start as a
    // silent hang and reading the dump instead of waiting out the full timeout.
    private val diagReadDelayMs = 28_000L

    private fun waitForDashboardThenShow(port: Int) {
        val deadline = System.currentTimeMillis() + dashboardPollTimeoutMs
        var diagShown = false
        while (System.currentTimeMillis() < deadline) {
            if (isDashboardResponding(port)) {
                mainHandler.post { showDashboard(port) }
                return
            }
            // No adb/PC for this device — the server thread's own crash is the
            // only useful diagnostic, so surface it immediately instead of
            // waiting out the full timeout and showing a generic message.
            runtime.lastError?.let { error ->
                mainHandler.post { showFatalError(error) }
                return
            }
            if (!diagShown && System.currentTimeMillis() - (deadline - dashboardPollTimeoutMs) >= diagReadDelayMs &&
                runtime.diagFile.exists()
            ) {
                diagShown = true
                val dump = runtime.diagFile.readText()
                mainHandler.post {
                    showFatalError(
                        IllegalStateException(
                            "Le démarrage semble bloqué (aucune exception, aucune réponse) — " +
                                "traces de tous les threads Python :\n\n$dump"))
                }
                return
            }
            Thread.sleep(dashboardPollIntervalMs)
        }
        mainHandler.post {
            showFatalError(
                IllegalStateException(
                    "Le tableau de bord Hermès n'a pas répondu sur 127.0.0.1:$port " +
                        "après ${dashboardPollTimeoutMs / 1000} secondes, sans exception " +
                        "remontée par le thread serveur."))
        }
    }

    private fun isDashboardResponding(port: Int): Boolean {
        return try {
            (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 1000
                readTimeout = 1000
                requestMethod = "GET"
            }.responseCode in 200..499
        } catch (_: Exception) {
            false
        }
    }

    private fun showFatalError(t: Throwable) {
        Log.e("MainActivity", "Échec du démarrage du runtime Hermès", t)
        val trace = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
        // Exceptions from Chaquopy (PyException wrapping SystemExit, in
        // particular) can lose the actual message hermes_cli printed before
        // exiting — that text landed in stdioFile instead (see
        // HermesRuntime.start), so always show both.
        val stdio = runtime.stdioFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val stdioSection = if (stdio.isNotEmpty()) "\n\n--- stdout/stderr Python ---\n$stdio" else ""
        statusView.apply {
            gravity = Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 11f
            text = "Échec du démarrage du runtime Hermès :\n\n$trace$stdioSection"
        }
    }

    private fun showDashboard(port: Int) {
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams,
                ): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback
                    return try {
                        fileChooserLauncher.launch(params.createIntent())
                        true
                    } catch (e: Exception) {
                        filePathCallback = null
                        false
                    }
                }
            }
            addJavascriptInterface(HermesFolderBridge(), "HermesAndroid")
        }
        this.webView = webView
        container.removeAllViews()
        container.addView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        webView.loadUrl("http://127.0.0.1:$port/")
    }

    // ---- Folder import (Storage Access Framework) ----------------------------------

    /** Exposed to the dashboard's JS as `window.HermesAndroid.pickFolder()`. */
    private inner class HermesFolderBridge {
        @JavascriptInterface
        fun pickFolder() {
            // @JavascriptInterface methods run on a WebView-owned worker thread, not
            // the UI thread — ActivityResultLauncher.launch() requires the UI thread.
            mainHandler.post { folderPickerLauncher.launch(null) }
        }
    }

    private fun handleFolderPicked(treeUri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers don't support persistable grants; the one-shot grant from
            // ACTION_OPEN_DOCUMENT_TREE is still enough to complete this single walk.
        }
        Thread({ importFolderTree(treeUri) }, "hermes-folder-import").start()
    }

    // Pragmatic guard against pathological trees, not a hard product requirement.
    private val folderImportMaxFiles = 500

    private fun importFolderTree(treeUri: Uri) {
        val wv = webView ?: return
        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null || !root.isDirectory) {
            pushToJs(wv, "onError", JSONObject().put("message", "Impossible d'ouvrir le dossier choisi."))
            return
        }
        val files = mutableListOf<Pair<String, DocumentFile>>()
        collectFiles(root, root.name ?: "dossier", files)
        if (files.size > folderImportMaxFiles) {
            pushToJs(
                wv, "onError",
                JSONObject().put(
                    "message",
                    "Le dossier contient plus de $folderImportMaxFiles fichiers ; import annulé.",
                ),
            )
            return
        }
        for ((relativePath, doc) in files) {
            try {
                val bytes = contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
                    ?: throw IOException("openInputStream a renvoyé null")
                val dataUrl = "data:${doc.type ?: "application/octet-stream"};base64," +
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                pushToJs(
                    wv, "onFile",
                    JSONObject().apply {
                        put("name", doc.name ?: relativePath.substringAfterLast('/'))
                        put("relativePath", relativePath)
                        put("dataUrl", dataUrl)
                    },
                )
            } catch (e: Exception) {
                pushToJs(wv, "onError", JSONObject().put("message", "$relativePath : ${e.message}"))
            }
        }
        mainHandler.post {
            wv.evaluateJavascript(
                "window.__HERMES_FOLDER_IMPORT__ && window.__HERMES_FOLDER_IMPORT__.onDone(${files.size});",
                null,
            )
        }
    }

    private fun collectFiles(dir: DocumentFile, relPrefix: String, out: MutableList<Pair<String, DocumentFile>>) {
        if (out.size > folderImportMaxFiles) return
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val childPath = "$relPrefix/$name"
            if (child.isDirectory) collectFiles(child, childPath, out)
            else if (child.isFile) out += childPath to child
        }
    }

    // One evaluateJavascript call per file (never one call with everything inlined) —
    // bounds each JS string to a single file's payload; JSONObject.toString() is
    // already valid, correctly-escaped JS, so no manual string-escaping is needed.
    private fun pushToJs(wv: WebView, fn: String, payload: JSONObject) {
        mainHandler.post {
            wv.evaluateJavascript(
                "window.__HERMES_FOLDER_IMPORT__ && window.__HERMES_FOLDER_IMPORT__.$fn($payload);",
                null,
            )
        }
    }
}
