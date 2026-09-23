package com.hermes.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * V2 UI client for the persistent Hermès runtime.
 *
 * The embedded Python runtime is owned by [HermesForegroundService], not by
 * this Activity. Closing or recreating the UI therefore does not stop Jarvis.
 */
class MainActivity : ComponentActivity() {

    private lateinit var container: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var statusScroll: ScrollView
    private var webView: WebView? = null
    private var dashboardPort: Int? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: HermesForegroundService? = null
    private var serviceBound = false

    private val serviceListener = HermesForegroundService.Listener { snapshot ->
        mainHandler.post { renderSnapshot(snapshot) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? HermesForegroundService.LocalBinder ?: return
            val connected = localBinder.service()
            service = connected
            serviceBound = true
            connected.addListener(serviceListener)
            connected.ensureRuntimeStarted()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            service = null
            showStatus("Connexion au service Hermès perdue. Reconnexion au prochain affichage…")
        }
    }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            val uris = FileChooserParams.parseResult(result.resultCode, result.data)
            Toast.makeText(
                this,
                "Sélecteur : ${uris?.size ?: 0} fichier(s)",
                Toast.LENGTH_SHORT,
            ).show()
            callback?.onReceiveValue(uris)
        }

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) handleFolderPicked(treeUri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildRootUi()
        showStatus("Connexion au service Jarvis Hermès…")
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, HermesForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (serviceBound) {
            service?.removeListener(serviceListener)
            unbindService(serviceConnection)
            serviceBound = false
            service = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        // Deliberately DO NOT stop HermesForegroundService or HermesRuntime here.
        // V2 keeps Jarvis alive when this Activity disappears.
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun buildRootUi() {
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        container.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        }
        statusScroll = ScrollView(this).apply {
            addView(
                statusView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        setContentView(container)
    }

    private fun renderSnapshot(snapshot: HermesForegroundService.Snapshot) {
        when (snapshot.phase) {
            HermesForegroundService.Phase.IDLE,
            HermesForegroundService.Phase.STARTING -> showStatus(snapshot.statusMessage)

            HermesForegroundService.Phase.RUNNING -> {
                if (webView == null || dashboardPort != snapshot.port) {
                    showDashboard(snapshot.port)
                }
            }

            HermesForegroundService.Phase.FAILED -> showFailure(snapshot)
        }
    }

    private fun showStatus(message: String) {
        if (statusScroll.parent == null) {
            webView?.let {
                container.removeView(it)
                it.destroy()
            }
            webView = null
            dashboardPort = null
            container.removeAllViews()
            container.addView(
                statusScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        statusView.apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT
            textSize = 16f
            text = message
        }
    }

    private fun showFailure(snapshot: HermesForegroundService.Snapshot) {
        val stdio = readDiagnostic(snapshot.stdioPath)
        val diag = readDiagnostic(snapshot.diagPath)
        val details = buildString {
            append("Échec du runtime Hermès.\n\n")
            append(snapshot.errorMessage ?: snapshot.statusMessage)
            if (stdio.isNotBlank()) {
                append("\n\n--- stdout/stderr Python ---\n")
                append(stdio)
            }
            if (diag.isNotBlank()) {
                append("\n\n--- traces Python ---\n")
                append(diag)
            }
        }
        showStatus(details)
        statusView.apply {
            gravity = Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
    }

    private fun readDiagnostic(path: String?): String {
        if (path.isNullOrBlank()) return ""
        return runCatching {
            val file = File(path)
            if (!file.exists()) "" else file.readText().takeLast(MAX_DIAGNOSTIC_CHARS)
        }.getOrDefault("")
    }

    private fun showDashboard(port: Int) {
        val view = WebView(this).apply {
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
                    } catch (_: Exception) {
                        filePathCallback = null
                        false
                    }
                }
            }
            addJavascriptInterface(HermesFolderBridge(), "HermesAndroid")
        }
        webView?.destroy()
        webView = view
        dashboardPort = port
        container.removeAllViews()
        container.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        view.loadUrl("http://127.0.0.1:$port/")
    }

    // ---- Folder import (Storage Access Framework) -------------------------------

    inner class HermesFolderBridge {
        @JavascriptInterface
        fun pickFolder() {
            mainHandler.post { folderPickerLauncher.launch(null) }
        }
    }

    private fun handleFolderPicked(treeUri: Uri) {
        Toast.makeText(this, "Dossier choisi, lecture en cours…", Toast.LENGTH_SHORT).show()
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // One-shot grant remains sufficient for the current import.
        }
        Thread({ importFolderTree(treeUri) }, "hermes-folder-import").start()
    }

    private fun importFolderTree(treeUri: Uri) {
        val view = webView ?: return
        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null || !root.isDirectory) {
            pushToJs(
                view,
                "onError",
                JSONObject().put("message", "Impossible d'ouvrir le dossier choisi."),
            )
            return
        }

        val files = mutableListOf<Pair<String, DocumentFile>>()
        collectFiles(root, root.name ?: "dossier", files)
        if (files.size > FOLDER_IMPORT_MAX_FILES) {
            pushToJs(
                view,
                "onError",
                JSONObject().put(
                    "message",
                    "Le dossier contient plus de $FOLDER_IMPORT_MAX_FILES fichiers ; import annulé.",
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
                    view,
                    "onFile",
                    JSONObject().apply {
                        put("name", doc.name ?: relativePath.substringAfterLast('/'))
                        put("relativePath", relativePath)
                        put("dataUrl", dataUrl)
                    },
                )
            } catch (e: Exception) {
                pushToJs(
                    view,
                    "onError",
                    JSONObject().put("message", "$relativePath : ${e.message}"),
                )
            }
        }

        mainHandler.post {
            view.evaluateJavascript(
                "window.__HERMES_FOLDER_IMPORT__ && window.__HERMES_FOLDER_IMPORT__.onDone(${files.size});",
                null,
            )
            Toast.makeText(
                this,
                "${files.size} fichier(s) importé(s).",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun collectFiles(
        dir: DocumentFile,
        relPrefix: String,
        out: MutableList<Pair<String, DocumentFile>>,
    ) {
        if (out.size > FOLDER_IMPORT_MAX_FILES) return
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val childPath = "$relPrefix/$name"
            if (child.isDirectory) collectFiles(child, childPath, out)
            else if (child.isFile) out += childPath to child
        }
    }

    private fun pushToJs(view: WebView, fn: String, payload: JSONObject) {
        mainHandler.post {
            view.evaluateJavascript(
                "window.__HERMES_FOLDER_IMPORT__ && window.__HERMES_FOLDER_IMPORT__.$fn($payload);",
                null,
            )
        }
    }

    companion object {
        private const val FOLDER_IMPORT_MAX_FILES = 500
        private const val MAX_DIAGNOSTIC_CHARS = 64_000
    }
}
