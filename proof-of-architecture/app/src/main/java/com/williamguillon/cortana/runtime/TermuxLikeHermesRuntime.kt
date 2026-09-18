package com.williamguillon.cortana.runtime

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * The implementation under test in Chantier 0.5. See proof-of-architecture/README.md and
 * jniLibs/README.md for the two things this class assumes exist on disk but this PR does not
 * ship: an arm64-v8a Python interpreter under nativeLibraryDir, and a Hermes source checkout
 * (plus its readable Python payload) under assets/runtime_payload/.
 *
 * Nothing here binds to anything other than 127.0.0.1, ever (spec §4.1 step 4).
 *
 * Per the "application installable" revision of the spec, [start] launches `hermes dashboard`
 * (the real web UI — StatusPage/ConfigPage/EnvPage/ChatPage — the same one desktop users get),
 * not `hermes serve`: `serve` sets `HERMES_SERVE_HEADLESS`, which explicitly disables the SPA
 * mount server-side (verified in `hermes_cli/web_server.py` / `hermes_cli/main_dashboard.py`).
 * [dashboardHttpUrl] is what the app's WebView loads once [start] returns. The JSON-RPC/WS
 * client ([baseUrl], used by the technical screen) still works against this same process — the
 * dashboard and serve commands share one gateway handler, dashboard just also mounts the SPA.
 */
class TermuxLikeHermesRuntime(private val ctx: Context) : HermesRuntime {

    @Volatile private var process: Process? = null
    @Volatile private var handle: RuntimeHandle? = null
    @Volatile var lastNativeHealthcheck: NativeHealthcheckResult? = null
        private set

    override val baseUrl: String
        get() = "ws://127.0.0.1:${handle?.port ?: 0}"

    val dashboardHttpUrl: String
        get() = "http://127.0.0.1:${handle?.port ?: 0}"

    override suspend fun start(): RuntimeHandle = withContext(Dispatchers.IO) {
        installRuntime()
        val healthcheck = verifyNativeExtensions()
        if (healthcheck.fatalFailureCount != 0) {
            throw HermesRuntimeException(
                step = "native_healthcheck",
                message = "Fatal native import(s) failed: " +
                    healthcheck.results.filter { it.fatal && !it.ok }
                        .joinToString { "${it.module}: ${it.error}" },
            )
        }
        startBackendProcess()
    }

    /**
     * Steps 1-2 of spec §4.1 (bootstrap extraction + `pip install`), exposed standalone so the
     * technical screen's "Installer runtime" button (§4.4 point 3) can run them without also
     * running the native healthcheck or launching the backend. This — and [verifyNativeExtensions]
     * / [startBackendProcess] below — are debug-only entry points on the concrete class, used only
     * by the technical screen; every other consumer (per spec §4.1) still goes through
     * [HermesRuntime.start] alone.
     */
    suspend fun installRuntime() = withContext(Dispatchers.IO) {
        extractBootstrapIfNeeded()
        installDependencies()
        Unit
    }

    /** Step 3 of spec §4.1, exposed standalone for the "Vérifier extensions natives" debug button. */
    suspend fun verifyNativeExtensions(): NativeHealthcheckResult = withContext(Dispatchers.IO) {
        val healthcheck = NativeHealthcheck.run(
            pythonBinary = RuntimePaths.pythonBinary(ctx),
            scriptFile = RuntimePaths.healthcheckScript(ctx),
        )
        lastNativeHealthcheck = healthcheck
        healthcheck
    }

    /**
     * Steps 4-5 of spec §4.1: launches `hermes dashboard` and waits for it to actually answer on
     * its HTTP port before returning — a spawned process is not the same as a ready server, and
     * callers (the main dashboard screen, the technical screen) both need "ready", not just
     * "started". Does NOT re-check the native healthcheck gate itself — callers (namely [start])
     * are responsible for that; the technical screen only reaches this after its own "Vérifier
     * extensions natives" step has passed (spec §4.4 point 5: "n'avance que si l'étape 4 est
     * passée").
     */
    suspend fun startBackendProcess(): RuntimeHandle = withContext(Dispatchers.IO) {
        val port = findFreeLoopbackPort()
        val proc = launchDashboard(port)
        process = proc

        val newHandle = RuntimeHandle(
            pid = processPidOrMinusOne(proc),
            port = port,
            startedAtMs = System.currentTimeMillis(),
        )
        handle = newHandle
        if (!waitForDashboardReady(port)) {
            throw HermesRuntimeException(
                step = "start_hermes",
                message = "hermes dashboard did not answer on 127.0.0.1:$port in time",
            )
        }
        newHandle
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        process?.let { proc ->
            proc.destroy()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        }
        process = null
        handle = null
    }

    override suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        val proc = process
        proc != null && proc.isAlive
    }

    /** Copies everything under assets/runtime_payload/ into filesDir/runtime/, once. See that dir's README. */
    private fun extractBootstrapIfNeeded() {
        val marker = RuntimePaths.extractedMarker(ctx)
        if (marker.exists()) return

        val root = RuntimePaths.runtimeRoot(ctx)
        root.mkdirs()
        copyAssetDirRecursively("runtime_payload", root)
        copyAssetFile("healthcheck.py", RuntimePaths.healthcheckScript(ctx))
        RuntimePaths.hermesHome(ctx).mkdirs()
        marker.writeText(System.currentTimeMillis().toString())
    }

    private fun copyAssetDirRecursively(assetPath: String, destDir: File) {
        val entries = ctx.assets.list(assetPath) ?: return
        if (entries.isEmpty()) {
            // Leaf file, not a directory.
            copyAssetFile(assetPath, File(destDir, assetPath.substringAfterLast('/')))
            return
        }
        destDir.mkdirs()
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val subEntries = ctx.assets.list(childAssetPath)
            if (subEntries != null && subEntries.isNotEmpty()) {
                copyAssetDirRecursively(childAssetPath, File(destDir, entry))
            } else {
                copyAssetFile(childAssetPath, File(destDir, entry))
            }
        }
    }

    private fun copyAssetFile(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        ctx.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /**
     * Spike-only dependency install (plan v2 §1.2). `-c constraints-termux.txt` is only added if
     * that file is actually present in the extracted source tree — see top-level README's
     * documented gap: this repo does not have that file at the root yet.
     */
    private fun installDependencies() {
        val srcDir = RuntimePaths.hermesSrcDir(ctx)
        val command = mutableListOf(
            RuntimePaths.pythonBinary(ctx).absolutePath, "-m", "pip", "install", "-e", ".[termux]",
        )
        val constraints = RuntimePaths.constraintsFile(ctx)
        if (constraints.exists()) {
            command += listOf("-c", constraints.name)
        }
        val proc = ProcessBuilder(command)
            .directory(srcDir)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(20, TimeUnit.MINUTES)
        val exitCode = if (finished) proc.exitValue() else {
            proc.destroyForcibly()
            -1
        }
        if (exitCode != 0) {
            throw HermesRuntimeException(
                step = "install_runtime",
                message = "pip install exited $exitCode:\n${output.takeLast(4000)}",
            )
        }
    }

    /**
     * `dashboard`, not `serve` — deliberately does NOT set `HERMES_SERVE_HEADLESS` (that flag is
     * what turns the SPA mount off). `--no-open` and `--skip-build` are real flags on `hermes
     * dashboard` (verified in `hermes_cli/subcommands/dashboard.py`): `--no-open` skips
     * `webbrowser.open()` (meaningless on Android anyway), `--skip-build` is required because
     * there is no Node/npm on this runtime to build the web UI on-device — `hermes_cli/web_dist/`
     * must already exist in the extracted source tree (see top-level README's documented gap:
     * this PR does not yet bundle the full Hermes source tree, `web_dist` included).
     */
    private fun launchDashboard(port: Int): Process {
        val hermesHome = RuntimePaths.hermesHome(ctx)
        val env = mapOf(
            "HERMES_HOME" to hermesHome.absolutePath,
            "ANDROID_API_LEVEL" to Build.VERSION.SDK_INT.toString(),
            "HOME" to ctx.filesDir.absolutePath,
        )
        val command = listOf(
            RuntimePaths.pythonBinary(ctx).absolutePath, "-m", "hermes_cli.main",
            "dashboard", "--isolated", "--no-open", "--skip-build",
            "--host", "127.0.0.1", "--port", port.toString(),
        )
        val builder = ProcessBuilder(command)
            .directory(RuntimePaths.hermesSrcDir(ctx))
            .redirectErrorStream(true)
        builder.environment().putAll(env)
        return builder.start()
    }

    /** Polls the dashboard's own HTTP port until it answers, rather than guessing a fixed delay. */
    private fun waitForDashboardReady(port: Int, timeoutMs: Long = 60_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) return false
            val reachable = runCatching {
                (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1000
                    readTimeout = 1000
                    requestMethod = "GET"
                }.responseCode in 200..499 // any real HTTP response means the server is up
            }.getOrDefault(false)
            if (reachable) return true
            Thread.sleep(500)
        }
        return false
    }

    /**
     * We pick the port ourselves (rather than `--port 0` + parsing stdout for the bound port,
     * which this spike could not confirm a stable log format for — see top-level README) and pass
     * it explicitly. Binds and immediately releases a loopback socket to get an OS-assigned free
     * port with a low (not zero) race window before hermes serve binds it itself.
     */
    private fun findFreeLoopbackPort(): Int =
        ServerSocket(0).use { it.localPort }

    // Process.pid() (java.lang.Process, API 26+) isn't resolvable against this project's compile
    // SDK stub, so it's called reflectively rather than as a typed member — the method exists at
    // runtime on API 26+ regardless of what the compile-time stub declares.
    private fun processPidOrMinusOne(process: Process): Int = runCatching {
        (process.javaClass.getMethod("pid").invoke(process) as Long).toInt()
    }.getOrDefault(-1)
}
