package com.williamguillon.cortana.runtime

/**
 * Abstraction over "a running Hermes backend on this device". Nothing outside this package
 * (the JSON-RPC client, the technical screen) may depend on [TermuxLikeHermesRuntime] internals —
 * only on this interface. This is what lets the implementation be swapped without touching the
 * rest of the app if the Termux-style bootstrap approach this spike is testing turns out not to
 * work (per Chantier 0.5 directives §4.1 — this abstraction is a decision already made, not to be
 * re-litigated here).
 */
interface HermesRuntime {
    suspend fun start(): RuntimeHandle
    suspend fun stop()
    suspend fun isHealthy(): Boolean

    /** e.g. "ws://127.0.0.1:8765" — always loopback, never a routable address (spec §4.1 step 4). */
    val baseUrl: String
}

data class RuntimeHandle(
    val pid: Int,
    val port: Int,
    val startedAtMs: Long,
)

/** Thrown by [HermesRuntime.start] when a step in the boot sequence fails. [step] names which one. */
class HermesRuntimeException(
    val step: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
