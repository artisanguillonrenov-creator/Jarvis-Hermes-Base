package com.hermes.android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
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
        container.addView(
            statusView,
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

    private fun waitForDashboardThenShow(port: Int) {
        val deadline = System.currentTimeMillis() + dashboardPollTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isDashboardResponding(port)) {
                mainHandler.post { showDashboard(port) }
                return
            }
            Thread.sleep(dashboardPollIntervalMs)
        }
        mainHandler.post {
            showFatalError(
                IllegalStateException(
                    "Le tableau de bord Hermès n'a pas répondu sur 127.0.0.1:$port " +
                        "après ${dashboardPollTimeoutMs / 1000} secondes."))
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
        statusView.text = "Échec du démarrage du runtime Hermès :\n${t.javaClass.simpleName}: ${t.message}"
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
