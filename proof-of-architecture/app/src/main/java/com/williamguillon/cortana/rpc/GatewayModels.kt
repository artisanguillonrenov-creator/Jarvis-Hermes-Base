package com.williamguillon.cortana.rpc

/** One `event` frame's `params`: `{"type": ..., "session_id": ..., "payload": {...}}` (tui_gateway/server.py). */
data class GatewayEvent(
    val type: String,
    val sessionId: String?,
    val payload: org.json.JSONObject?,
)

sealed class GatewayCallResult {
    data class Success(val result: org.json.JSONObject) : GatewayCallResult()
    data class Failure(val code: Int?, val message: String?) : GatewayCallResult()
}

/**
 * Fixed, documented policy for the spike (spec §5): there is no real approval UI here, so every
 * server→client `approval.request` is auto-denied via `approval.respond`. This exists purely so
 * the agent thread never blocks indefinitely waiting on an approval that can never come from a
 * human in this build — it is NOT a security stance for any future Cortana build.
 */
const val FIXED_APPROVAL_POLICY_CHOICE = "deny"
