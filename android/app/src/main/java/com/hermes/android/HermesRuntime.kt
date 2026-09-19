package com.hermes.android

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Boundary between the Android app and the embedded Hermès (Python) runtime.
 *
 * Backed by Chaquopy: [start] boots a real CPython 3.12 interpreter embedded in
 * the APK and calls straight into `hermes_cli.main.main()` — the same entry
 * point `hermes dashboard` uses from a normal checkout — with `sys.argv` and
 * `HERMES_HOME` set for this app's sandbox. No PC, server, or network
 * dependency: everything (interpreter, hermes-agent's source tree, its
 * third-party dependencies, and the prebuilt dashboard SPA) is bundled in the
 * APK by Chaquopy at build time.
 */
interface HermesRuntime {
    /** Loopback port `hermes dashboard` will be reachable on once started. */
    val port: Int

    /**
     * Starts the Hermès dashboard server on a dedicated background thread and
     * returns immediately (it does not itself wait for the server to be
     * listening — the caller polls [port]). Throws only if the interpreter or
     * the initial dispatch into `hermes_cli.main.main()` cannot be started at
     * all; runtime failures inside the server surface as that thread dying,
     * which shows up as [port] never answering.
     */
    fun start(onStatus: (String) -> Unit)

    fun stop()

    companion object {
        fun create(context: Context): HermesRuntime = ChaquopyHermesRuntime(context)
    }
}

private class ChaquopyHermesRuntime(private val context: Context) : HermesRuntime {
    override val port: Int = 9119

    @Volatile
    private var serverThread: Thread? = null

    override fun start(onStatus: (String) -> Unit) {
        onStatus("Démarrage du runtime Python…")

        val hermesHome = context.filesDir.resolve(".hermes").apply { mkdirs() }.absolutePath
        val appContext = context.applicationContext

        // hermes_cli.main installs signal handlers (SIGTERM hangup protection),
        // and Python only allows that from the interpreter's "main thread" —
        // whichever thread first calls Python.start(). So Python.start() and
        // hermes_cli.main.main() both run on this one dedicated thread, never
        // the caller's thread or the UI thread.
        val thread = Thread({
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(appContext))
                }
                val py = Python.getInstance()

                val os = py.getModule("os")
                val environ = os["environ"]
                environ!!.callAttr("__setitem__", "HERMES_HOME", hermesHome)
                environ.callAttr("__setitem__", "HOME", appContext.filesDir.absolutePath)

                val sys = py.getModule("sys")
                sys.put(
                    "argv",
                    arrayOf(
                        "hermes", "dashboard",
                        "--port", port.toString(),
                        "--host", "127.0.0.1",
                        "--skip-build",
                        "--no-open",
                    ),
                )

                onStatus("Lancement du tableau de bord Hermès…")
                py.getModule("hermes_cli.main").callAttr("main")
            } catch (e: PyException) {
                Log.e(TAG, "hermes_cli.main.main() a levé une exception Python", e)
            } catch (t: Throwable) {
                Log.e(TAG, "Le thread du serveur Hermès s'est arrêté de façon inattendue", t)
            }
        }, "hermes-dashboard-server")
        thread.isDaemon = true
        serverThread = thread
        thread.start()
    }

    override fun stop() {
        // `hermes dashboard` runs uvicorn's blocking Server.run() with no
        // in-process handle exposed here to ask it to shut down gracefully;
        // the daemon thread (and the whole embedded interpreter) is torn down
        // with the process when the Activity/app dies, which is acceptable
        // for this single-window app.
    }

    companion object {
        private const val TAG = "HermesRuntime"
    }
}
