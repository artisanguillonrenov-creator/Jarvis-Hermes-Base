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
 * V2 keeps one process-wide runtime instance so Android Service recreation can
 * never start a second dashboard server on the same port.
 */
interface HermesRuntime {
    val port: Int
    val lastError: Throwable?
    val diagFile: File
    val stdioFile: File

    fun start(onStatus: (String) -> Unit)
    fun stop()

    companion object {
        @Volatile
        private var instance: HermesRuntime? = null

        fun create(context: Context): HermesRuntime {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ChaquopyHermesRuntime(context.applicationContext).also {
                    instance = it
                }
            }
        }
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

    @Synchronized
    override fun start(onStatus: (String) -> Unit) {
        val existing = serverThread
        if (existing?.isAlive == true) {
            onStatus("Runtime Hermès déjà actif.")
            return
        }

        lastError = null
        onStatus("Démarrage du runtime Python…")

        val hermesHome = context.filesDir.resolve(".hermes").apply { mkdirs() }.absolutePath
        val appContext = context.applicationContext

        // Python.start() and hermes_cli.main.main() stay on the same dedicated
        // thread because hermes_cli installs signal handlers during startup.
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

                // Line-buffered so logs survive hard failures as far as possible.
                stdioFile.delete()
                val stdio = py.getBuiltins()
                    .callAttr("open", stdioFile.absolutePath, "w", Kwarg("buffering", 1))
                sys.put("stdout", stdio)
                sys.put("stderr", stdio)

                // If startup hangs instead of throwing, keep a Python stack dump
                // which the Android UI can display without adb or a PC.
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
        // Intentionally non-destructive in V2. The embedded dashboard has no
        // in-process graceful shutdown handle yet. The process owns the runtime;
        // closing only the Activity or recreating the Service must not kill it.
    }

    companion object {
        private const val TAG = "HermesRuntime"
    }
}
