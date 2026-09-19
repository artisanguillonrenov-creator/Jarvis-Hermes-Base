package com.hermes.android

import android.content.Context
import android.util.Log
import com.chaquo.python.Kwarg
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

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
     * Non-null once the dedicated server thread has died from an uncaught
     * exception. There's no adb/PC in the loop for this app's target device,
     * so the only diagnostic channel is putting the real failure on screen —
     * callers should poll this instead of waiting out a generic timeout.
     */
    val lastError: Throwable?

    /**
     * Path to a diagnostic dump that fills in exactly when a hang wouldn't
     * otherwise raise anything into [lastError] — e.g. a call blocked forever
     * on I/O. Populated ~25s into the Python-side start attempt regardless of
     * outcome (see [start]); callers should only read it once they've decided
     * startup is stuck (no HTTP response, no [lastError]).
     */
    val diagFile: File

    /**
     * Captured `sys.stdout`/`sys.stderr` from the Python side. hermes_cli's
     * own error paths often `print()` the actual detail and then
     * `sys.exit(1)` with a bare exit code — the exception alone (Chaquopy
     * only bridges that exit code as a `PyException`) loses the message
     * entirely, so stdout/stderr are redirected here from the very start of
     * [start] instead of wherever Chaquopy would otherwise send them.
     */
    val stdioFile: File

    /**
     * Starts the Hermès dashboard server on a dedicated background thread and
     * returns immediately (it does not itself wait for the server to be
     * listening — the caller polls [port] and [lastError]). Throws only if the
     * interpreter or the initial dispatch into `hermes_cli.main.main()` cannot
     * be started at all; runtime failures inside the server surface via
     * [lastError].
     */
    fun start(onStatus: (String) -> Unit)

    fun stop()

    companion object {
        fun create(context: Context): HermesRuntime = ChaquopyHermesRuntime(context)
    }
}

private class ChaquopyHermesRuntime(private val context: Context) : HermesRuntime {
    override val port: Int = 9119
    override val diagFile: File = context.filesDir.resolve("hermes_diag.log")
    override val stdioFile: File = context.filesDir.resolve("hermes_stdio.log")

    @Volatile
    override var lastError: Throwable? = null
        private set

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
                // Covers agent/i18n.py's static strings (approval prompts, gateway
                // slash-command replies) — the dashboard's own UI locale is a
                // separate, browser-side default (web/src/i18n/context.tsx).
                environ.callAttr("__setitem__", "HERMES_LANGUAGE", "fr")

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

                // Line-buffered so a partial log survives a hard crash, not
                // just a clean exit.
                stdioFile.delete()
                val stdio = py.getBuiltins()
                    .callAttr("open", stdioFile.absolutePath, "w", Kwarg("buffering", 1))
                sys.put("stdout", stdio)
                sys.put("stderr", stdio)

                // Belt-and-suspenders for a hang with no exception (e.g. a
                // blocking call on stdin/network that never returns): dump
                // every thread's Python stack to a plain file after 25s,
                // readable from Kotlin with no adb/PC involved.
                diagFile.delete()
                val diagHandle = py.getBuiltins().callAttr("open", diagFile.absolutePath, "w")
                py.getModule("faulthandler")
                    .callAttr("dump_traceback_later", 25, Kwarg("file", diagHandle))

                onStatus("Lancement du tableau de bord Hermès…")
                py.getModule("hermes_cli.main").callAttr("main")
            } catch (e: PyException) {
                Log.e(TAG, "hermes_cli.main.main() a levé une exception Python", e)
                lastError = e
            } catch (t: Throwable) {
                Log.e(TAG, "Le thread du serveur Hermès s'est arrêté de façon inattendue", t)
                lastError = t
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
