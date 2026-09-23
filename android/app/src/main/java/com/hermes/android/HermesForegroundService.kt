package com.hermes.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Long-lived Android owner of the embedded Hermès runtime.
 *
 * The Activity is intentionally only a UI client. Keeping CPython and the
 * dashboard server here means closing/recreating the Activity no longer tears
 * down Jarvis. START_STICKY asks Android to recreate this service after a
 * process-level reclaim when policy allows it.
 */
class HermesForegroundService : Service() {

    enum class Phase { IDLE, STARTING, RUNNING, FAILED }

    data class Snapshot(
        val phase: Phase,
        val statusMessage: String,
        val port: Int,
        val errorMessage: String? = null,
        val stdioPath: String? = null,
        val diagPath: String? = null,
    )

    fun interface Listener {
        fun onSnapshot(snapshot: Snapshot)
    }

    inner class LocalBinder : Binder() {
        fun service(): HermesForegroundService = this@HermesForegroundService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val bootInProgress = AtomicBoolean(false)

    private lateinit var runtime: HermesRuntime
    private lateinit var files: HermesFileManager
    private lateinit var preferences: HermesServicePreferences

    @Volatile
    private var snapshot = Snapshot(
        phase = Phase.IDLE,
        statusMessage = "Service Hermès prêt.",
        port = 9119,
    )

    override fun onCreate() {
        super.onCreate()
        runtime = HermesRuntime.create(applicationContext)
        files = HermesFileManager(applicationContext)
        preferences = HermesServicePreferences(applicationContext)
        files.ensureLayout()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        preferences.serviceEnabled = true
        startForeground(NOTIFICATION_ID, buildNotification("Initialisation de Jarvis Hermès…"))
        ensureRuntimeStarted()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // HermesRuntime.stop() is deliberately non-destructive today: the Python
        // server has no in-process graceful-stop handle. If Android destroys only
        // the Service object while keeping the process alive, the singleton runtime
        // remains available for the recreated START_STICKY service.
        runtime.stop()
        super.onDestroy()
    }

    fun currentSnapshot(): Snapshot = snapshot

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onSnapshot(snapshot)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun fileManager(): HermesFileManager = files

    /** Idempotent: Activity rebinds never launch a second Python server. */
    fun ensureRuntimeStarted() {
        if (snapshot.phase == Phase.RUNNING) return
        if (!bootInProgress.compareAndSet(false, true)) return

        publish(Phase.STARTING, "Démarrage du runtime Python…")
        Thread({
            try {
                runtime.start { message -> publish(Phase.STARTING, message) }
                waitForDashboard()
            } catch (t: Throwable) {
                fail(t)
            } finally {
                bootInProgress.set(false)
            }
        }, "hermes-service-bootstrap").start()
    }

    private fun waitForDashboard() {
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + DASHBOARD_TIMEOUT_MS
        var diagChecked = false

        while (System.currentTimeMillis() < deadline) {
            if (dashboardResponding(runtime.port)) {
                preferences.lastSuccessfulStartEpochMs = System.currentTimeMillis()
                publish(
                    Phase.RUNNING,
                    "Jarvis Hermès actif en arrière-plan.",
                )
                return
            }

            runtime.lastError?.let {
                fail(it)
                return
            }

            if (!diagChecked && System.currentTimeMillis() - startedAt >= DIAG_READ_DELAY_MS) {
                diagChecked = true
                if (runtime.diagFile.exists()) {
                    val dump = runCatching { runtime.diagFile.readText() }.getOrDefault("")
                    fail(
                        IllegalStateException(
                            "Le démarrage Hermès semble bloqué. Traces Python :\n\n$dump",
                        ),
                    )
                    return
                }
            }

            Thread.sleep(DASHBOARD_POLL_MS)
        }

        fail(
            IllegalStateException(
                "Le tableau de bord Hermès n'a pas répondu sur 127.0.0.1:${runtime.port} " +
                    "après ${DASHBOARD_TIMEOUT_MS / 1000} secondes.",
            ),
        )
    }

    private fun dashboardResponding(port: Int): Boolean = try {
        val connection = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        connection.requestMethod = "GET"
        try {
            connection.responseCode in 200..499
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    private fun fail(t: Throwable) {
        Log.e(TAG, "Échec du runtime Hermès", t)
        publish(
            phase = Phase.FAILED,
            message = "Échec du runtime Hermès.",
            error = t.stackTraceToString(),
        )
    }

    private fun publish(phase: Phase, message: String, error: String? = null) {
        snapshot = Snapshot(
            phase = phase,
            statusMessage = message,
            port = runtime.port,
            errorMessage = error,
            stdioPath = runtime.stdioFile.absolutePath,
            diagPath = runtime.diagFile.absolutePath,
        )
        preferences.lastStatus = message
        updateNotification(message)
        for (listener in listeners) {
            runCatching { listener.onSnapshot(snapshot) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis Hermès",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Maintient le moteur Hermès actif en arrière-plan."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(message: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Jarvis Hermès")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "HermesForegroundService"
        private const val CHANNEL_ID = "hermes_runtime"
        private const val NOTIFICATION_ID = 9119
        private const val DASHBOARD_POLL_MS = 500L
        private const val DASHBOARD_TIMEOUT_MS = 60_000L
        private const val DIAG_READ_DELAY_MS = 28_000L
    }
}
