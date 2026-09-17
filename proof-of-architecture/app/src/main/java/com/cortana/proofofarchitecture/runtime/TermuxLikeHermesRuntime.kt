package com.cortana.proofofarchitecture.runtime

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * The implementation under test in Chantier 0.5. See proof-of-architecture/README.md and
 * jniLibs/README.md for the two things this class assumes exist on disk but this PR does not
 * ship: an arm64-v8a Python interpreter under nativeLibraryDir, and a Hermes source checkout
 * (plus its readable Python payload) under assets/runtime_payload/.
 *
 * Nothing here binds to anything other than 127.0.0.1, ever (spec §4.1 step 4).
 */
class TermuxLikeHermesRuntime(private val ctx: Context) : HermesRuntime {

    @Volatile private var process: Process? = null
    @Volatile private var handle: RuntimeHandle? = null
    @Volatile var lastNativeHealthcheck: NativeHealthcheckResult? = null
        private set

    override val baseUrl: String
        get() = "ws://127.0.0.1:${handle?.port ?: 0}"

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
     * Steps 4-5 of spec §4.1. Does NOT re-check the native healthcheck gate itself — callers
     * (namely [start]) are responsible for that; the technical screen only reaches this after its
     * own "Vérifier extensions natives" step has passed (spec §4.4 point 5: "n'avance que si
     * l'étape 4 est passée").
     */
    suspend fun startBackendProcess(): RuntimeHandle = withContext(Dispatchers.IO) {
        val port = findFreeLoopbackPort()
        val proc = launchHeadlessBackend(port)
        process = proc

        val newHandle = RuntimeHandle(
            pid = processPidOrMinusOne(proc),
            port = port,
            startedAtMs = System.currentTimeMillis(),
        )
        handle = newHandle
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

    /** Copies assets/runtime_payload/** into filesDir/runtime/, once. See that dir's README. */
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

    private fun launchHeadlessBackend(port: Int): Process {
        val hermesHome = RuntimePaths.hermesHome(ctx)
        val env = mapOf(
            "HERMES_HOME" to hermesHome.absolutePath,
            "HERMES_SERVE_HEADLESS" to "1",
            "ANDROID_API_LEVEL" to Build.VERSION.SDK_INT.toString(),
            "HOME" to ctx.filesDir.absolutePath,
        )
        val command = listOf(
            RuntimePaths.pythonBinary(ctx).absolutePath, "-m", "hermes_cli.main",
            "serve", "--isolated", "--host", "127.0.0.1", "--port", port.toString(),
        )
        val builder = ProcessBuilder(command)
            .directory(RuntimePaths.hermesSrcDir(ctx))
            .redirectErrorStream(true)
        builder.environment().putAll(env)
        return builder.start()
    }

    /**
     * We pick the port ourselves (rather than `--port 0` + parsing stdout for the bound port,
     * which this spike could not confirm a stable log format for — see top-level README) and pass
     * it explicitly. Binds and immediately releases a loopback socket to get an OS-assigned free
     * port with a low (not zero) race window before hermes serve binds it itself.
     */
    private fun findFreeLoopbackPort(): Int =
        ServerSocket(0).use { it.localPort }

    private fun processPidOrMinusOne(process: Process): Int =
        runCatching { process.pid().toInt() }.getOrDefault(-1)
}
