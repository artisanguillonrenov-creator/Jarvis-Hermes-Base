package com.hermes.android

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
class MainActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var statusView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtime: HermesRuntime by lazy { HermesRuntime.create(this) }

    private val dashboardPollIntervalMs = 500L
    private val dashboardPollTimeoutMs = 60_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
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
        }
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
}
