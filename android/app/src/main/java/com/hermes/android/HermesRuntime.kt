package com.hermes.android

import android.content.Context

/**
 * Boundary between the Android app and the embedded Hermès (Python) runtime.
 *
 * Phase 1 ships only [NotImplementedHermesRuntime]: it fails fast with a clear
 * message so the skeleton APK is honest about what isn't wired up yet. Phase 3
 * replaces the factory below with the real bootstrap-extract/pip-install/
 * healthcheck/`hermes dashboard` sequence.
 */
interface HermesRuntime {
    /** Loopback port `hermes dashboard` will be reachable on once started. */
    val port: Int

    /**
     * Blocking: performs whatever setup is needed and returns once
     * `hermes dashboard` is listening. [onStatus] is invoked (from any thread)
     * with human-readable progress messages. Throws on any fatal failure.
     */
    fun start(onStatus: (String) -> Unit)

    fun stop()

    companion object {
        fun create(context: Context): HermesRuntime = NotImplementedHermesRuntime(context)
    }
}

private class NotImplementedHermesRuntime(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : HermesRuntime {
    override val port: Int = 9119

    override fun start(onStatus: (String) -> Unit) {
        onStatus("Installation du runtime Hermès…")
        throw UnsupportedOperationException(
            "Le runtime Hermès (bootstrap Python/Termux) n'est pas encore embarqué " +
                "dans cette build — squelette Phase 1 uniquement.")
    }

    override fun stop() = Unit
}
