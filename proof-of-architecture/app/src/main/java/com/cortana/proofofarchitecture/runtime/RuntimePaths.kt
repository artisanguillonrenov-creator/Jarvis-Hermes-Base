package com.cortana.proofofarchitecture.runtime

import android.content.Context
import java.io.File

/**
 * Every on-device path [TermuxLikeHermesRuntime] touches, in one place, so the extraction step,
 * the install step and the diagnostics screen all agree on where things live.
 */
object RuntimePaths {
    /** Readable-only Python payload (see assets/runtime_payload/README.md for what's missing). */
    fun runtimeRoot(ctx: Context): File = File(ctx.filesDir, "runtime")

    /** Editable-install source tree expected at the root of the extracted payload. */
    fun hermesSrcDir(ctx: Context): File = File(runtimeRoot(ctx), "hermes-src")

    /** Optional — only passed to pip if it actually exists (see top-level README's documented gap). */
    fun constraintsFile(ctx: Context): File = File(hermesSrcDir(ctx), "constraints-termux.txt")

    /** HERMES_HOME: never SAF, always internal storage (rapport d'architecture principal). */
    fun hermesHome(ctx: Context): File = File(ctx.filesDir, ".hermes")

    fun envFile(ctx: Context): File = File(hermesHome(ctx), ".env")

    fun stateDb(ctx: Context): File = File(hermesHome(ctx), "state.db")

    fun diagnosticsDir(ctx: Context): File = File(ctx.filesDir, "diagnostics")

    /** healthcheck.py itself lives at the asset root, copied out once (see assets/healthcheck.py). */
    fun healthcheckScript(ctx: Context): File = File(runtimeRoot(ctx), "healthcheck.py")

    private const val EXTRACTED_MARKER_NAME = ".extracted"
    fun extractedMarker(ctx: Context): File = File(runtimeRoot(ctx), EXTRACTED_MARKER_NAME)

    /**
     * Only executable ELF binaries live under nativeLibraryDir (spec §4.1). The interpreter binary
     * itself is expected here, packaged as a fake "lib*.so" so Android's installer preserves its
     * executable bit — see jniLibs/README.md for why this file does not exist yet in this PR.
     */
    fun pythonBinary(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libpython.so")
}
