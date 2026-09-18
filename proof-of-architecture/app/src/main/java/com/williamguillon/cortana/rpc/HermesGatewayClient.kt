package com.williamguillon.cortana.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal JSON-RPC/WebSocket client for `tui_gateway`, covering only what Chantier 0.5's
 * acceptance chain needs (spec §5): `session.create`, `session.resume`, `prompt.submit`, the
 * `message.delta`/`message.complete` events, and an auto-deny handler for `approval.request` so
 * the agent thread never blocks forever on an approval this build cannot answer for real.
 *
 * DOCUMENTED DEVIATION from the original spec: there is no `client.capabilities` RPC in this
 * codebase (verified against tui_gateway/server.py, tui_gateway/ws.py — see top-level README).
 * The server instead *pushes* a `gateway.ready` event right after the WebSocket handshake
 * completes; [awaitReady] waits for that instead of sending a capabilities call that would just
 * come back "method not found".
 */
class HermesGatewayClient(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket
        .build()
    private var socket: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<GatewayCallResult>>()
    private val readySignal = CompletableDeferred<JSONObject>()

    var onMessageDelta: ((sessionId: String?, payload: JSONObject?) -> Unit)? = null
    var onMessageComplete: ((sessionId: String?, payload: JSONObject?) -> Unit)? = null
    var onApprovalRequest: ((sessionId: String?, requestId: String?) -> Unit)? = null

    fun connect() {
        val request = Request.Builder().url(baseUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                text.lineSequence().filter { it.isNotBlank() }.forEach(::handleFrame)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                if (!readySignal.isCompleted) readySignal.completeExceptionally(t)
                pending.values.forEach { it.complete(GatewayCallResult.Failure(null, t.message)) }
                pending.clear()
            }
        })
    }

    fun close() {
        socket?.close(1000, "spike shutdown")
        socket = null
    }

    /** Waits for the server-pushed `gateway.ready` event — see the class doc for why this replaces
     * a `client.capabilities` call that does not exist server-side. */
    suspend fun awaitReady(timeoutMs: Long = 10_000): JSONObject =
        withTimeout(timeoutMs) { readySignal.await() }

    suspend fun sessionCreate(source: String = "android"): GatewayCallResult =
        call("session.create", JSONObject().put("source", source))

    suspend fun sessionResume(sessionId: String, source: String = "android"): GatewayCallResult =
        call(
            "session.resume",
            JSONObject().put("session_id", sessionId).put("source", source),
        )

    suspend fun promptSubmit(sessionId: String, text: String): GatewayCallResult =
        call(
            "prompt.submit",
            JSONObject().put("session_id", sessionId).put("text", text),
        )

    private fun respondToApproval(sessionId: String, requestId: String?) {
        val params = JSONObject()
            .put("session_id", sessionId)
            .put("choice", FIXED_APPROVAL_POLICY_CHOICE)
        if (requestId != null) params.put("request_id", requestId)
        // Fire-and-forget: a slow/failed deny must not block the read loop.
        val id = nextId.getAndIncrement()
        val frame = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("method", "approval.respond").put("params", params)
        socket?.send(frame.toString())
    }

    private suspend fun call(method: String, params: JSONObject, timeoutMs: Long = 30_000): GatewayCallResult {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<GatewayCallResult>()
        pending[id] = deferred
        val frame = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val sent = socket?.send(frame.toString()) ?: false
        if (!sent) {
            pending.remove(id)
            return GatewayCallResult.Failure(null, "socket not connected")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun handleFrame(line: String) {
        val frame = runCatching { JSONObject(line) }.getOrNull() ?: return
        val id = frame.opt("id")
        if (frame.has("method") && frame.optString("method") == "event") {
            handleEvent(frame.optJSONObject("params"))
            return
        }
        if (id is Int || id is Long) {
            val key = (id as Number).toInt()
            val waiting = pending.remove(key) ?: return
            val error = frame.optJSONObject("error")
            if (error != null) {
                waiting.complete(GatewayCallResult.Failure(error.optInt("code"), error.optString("message")))
            } else {
                waiting.complete(GatewayCallResult.Success(frame.optJSONObject("result") ?: JSONObject()))
            }
        }
    }

    private fun handleEvent(params: JSONObject?) {
        params ?: return
        val type = params.optString("type")
        val sessionId = params.optString("session_id").takeIf { it.isNotBlank() }
        val payload = params.optJSONObject("payload")
        when (type) {
            "gateway.ready" -> if (!readySignal.isCompleted) readySignal.complete(payload ?: JSONObject())
            "message.delta" -> onMessageDelta?.invoke(sessionId, payload)
            "message.complete" -> onMessageComplete?.invoke(sessionId, payload)
            "approval.request" -> {
                val requestId = payload?.optString("request_id")?.takeIf { it.isNotBlank() }
                onApprovalRequest?.invoke(sessionId, requestId)
                if (sessionId != null) respondToApproval(sessionId, requestId)
            }
        }
    }
}
