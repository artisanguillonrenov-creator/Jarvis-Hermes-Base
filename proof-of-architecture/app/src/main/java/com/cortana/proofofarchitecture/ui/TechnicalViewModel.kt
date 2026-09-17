package com.cortana.proofofarchitecture.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cortana.proofofarchitecture.db.WalChecker
import com.cortana.proofofarchitecture.diagnostics.DiagnosticExporter
import com.cortana.proofofarchitecture.diagnostics.DiagnosticReportBuilder
import com.cortana.proofofarchitecture.diagnostics.DiagnosticStep
import com.cortana.proofofarchitecture.diagnostics.StepStatus
import com.cortana.proofofarchitecture.rpc.GatewayCallResult
import com.cortana.proofofarchitecture.rpc.HermesGatewayClient
import com.cortana.proofofarchitecture.runtime.HermesRuntimeException
import com.cortana.proofofarchitecture.runtime.TermuxLikeHermesRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Backs the technical screen's 13 buttons (spec §4.4), in the same logical order, and assembles
 * the diagnostic report (spec §4.5) as they run. Every step's real outcome is recorded — a step
 * that fails stays recorded as failed, it is never quietly softened to make the report look
 * greener (spec §0, §7: "ne jamais faire en sorte qu'un test 'passe' en assouplissant silencieusement
 * ce qu'il vérifie").
 */
class TechnicalViewModel(app: Application) : AndroidViewModel(app) {

    private val runtime = TermuxLikeHermesRuntime(app.applicationContext)
    private val report = DiagnosticReportBuilder(app.applicationContext)
    private var gateway: HermesGatewayClient? = null
    private var lastSessionId: String? = null
    private var lastAssistantReply: String? = null
    private var messageCompleteSignal: kotlinx.coroutines.CompletableDeferred<String?>? = null

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private fun logLine(line: String) {
        _log.value = _log.value + line
    }

    private suspend fun <T> timedStep(name: String, block: suspend () -> T): Pair<Boolean, T?> {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            val duration = System.currentTimeMillis() - start
            report.addStep(DiagnosticStep(name, StepStatus.OK, duration))
            logLine("[$name] ok (${duration}ms)")
            true to result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            val detail = if (e is HermesRuntimeException) "${e.step}: ${e.message}" else e.message.orEmpty()
            report.addStep(DiagnosticStep(name, StepStatus.FAILED, duration, detail))
            logLine("[$name] FAILED (${duration}ms): $detail")
            _status.value = "error: $name"
            false to null
        }
    }

    fun installRuntime() = viewModelScope.launch {
        _status.value = "installing"
        timedStep("install_runtime") { runtime.installRuntime() }
        _status.value = "installed"
    }

    fun verifyNativeExtensions() = viewModelScope.launch {
        _status.value = "verifying native extensions"
        // The raw per-module results are recorded in the report BEFORE the pass/fail gate below —
        // a fatal import failure is exactly the case where this detail matters most (spec §4.3:
        // "c'est ce qui permet de savoir exactement quelle dépendance a posé problème").
        val (ok, _) = timedStep("native_healthcheck") {
            val r = runtime.verifyNativeExtensions()
            report.setNativeHealthcheck(r)
            if (r.fatalFailureCount != 0) {
                throw HermesRuntimeException(
                    "native_healthcheck",
                    "fatal_failure_count=${r.fatalFailureCount}: " +
                        r.results.filter { it.fatal && !it.ok }.joinToString { "${it.module}: ${it.error}" },
                )
            }
            r
        }
        _status.value = if (ok) "native extensions ok" else "native extensions FAILED"
    }

    fun startHermes() = viewModelScope.launch {
        _status.value = "starting hermes"
        val (ok, handle) = timedStep("start_hermes") { runtime.startBackendProcess() }
        if (ok && handle != null) {
            gateway = HermesGatewayClient(runtime.baseUrl).also { client ->
                client.onMessageComplete = { _, payload ->
                    lastAssistantReply = payload?.optString("text")
                    messageCompleteSignal?.complete(lastAssistantReply)
                }
                client.connect()
            }
            _status.value = "hermes running pid=${handle.pid} port=${handle.port}"
        } else {
            _status.value = "start_hermes FAILED"
        }
    }

    fun testWebSocket() = viewModelScope.launch {
        val client = gateway ?: run { logLine("[websocket_connect] no gateway client — start Hermes first"); return@launch }
        timedStep("websocket_connect") { client.awaitReady() }
    }

    fun testConversation() = viewModelScope.launch {
        val client = gateway ?: run { logLine("[conversation_roundtrip] no gateway client"); return@launch }
        timedStep("conversation_roundtrip") {
            val created = client.sessionCreate(source = "android")
            val sessionId = (created as? GatewayCallResult.Success)?.result?.optString("session_id")
                ?: throw HermesRuntimeException("conversation_roundtrip", "session.create failed: $created")
            lastSessionId = sessionId

            messageCompleteSignal = kotlinx.coroutines.CompletableDeferred()
            val submitted = client.promptSubmit(sessionId, "Say the single word: acknowledged.")
            if (submitted !is GatewayCallResult.Success) {
                throw HermesRuntimeException("conversation_roundtrip", "prompt.submit failed: $submitted")
            }
            val reply = withTimeoutOrNull(60_000) { messageCompleteSignal?.await() }
                ?: throw HermesRuntimeException("conversation_roundtrip", "no message.complete within 60s")
            reply
        }
    }

    fun killBackend() = viewModelScope.launch {
        timedStep("kill_backend") {
            gateway?.close()
            gateway = null
            runtime.stop()
        }
        _status.value = "stopped"
    }

    fun restartBackend() = viewModelScope.launch {
        _status.value = "restarting"
        val (ok, handle) = timedStep("restart_backend") { runtime.startBackendProcess() }
        if (ok && handle != null) {
            gateway = HermesGatewayClient(runtime.baseUrl).also { client ->
                client.onMessageComplete = { _, payload ->
                    lastAssistantReply = payload?.optString("text")
                    messageCompleteSignal?.complete(lastAssistantReply)
                }
                client.connect()
            }
            _status.value = "hermes running pid=${handle.pid} port=${handle.port}"
        }
    }

    fun testResume() = viewModelScope.launch {
        val client = gateway ?: run { logLine("[session_resume] no gateway client"); return@launch }
        val sessionId = lastSessionId ?: run { logLine("[session_resume] no prior session"); return@launch }
        val start = System.currentTimeMillis()
        try {
            client.awaitReady()
            val resumed = client.sessionResume(sessionId, source = "android")
            if (resumed !is GatewayCallResult.Success) {
                throw HermesRuntimeException("session_resume", "session.resume failed: $resumed")
            }
            // Best-effort content check: session.resume's exact history field shape was not
            // pinned down against a live server by this session (no device to test against) —
            // this substring match on the raw response body is deliberately conservative rather
            // than asserting a field name this PR could not verify end-to-end.
            val reply = lastAssistantReply
            val matches = reply != null && resumed.result.toString().contains(reply)
            val duration = System.currentTimeMillis() - start
            report.addStep(
                DiagnosticStep(
                    "session_resume", StepStatus.OK, duration,
                    extra = mapOf("content_matches_original" to matches),
                ),
            )
            logLine("[session_resume] ok (${duration}ms) content_matches_original=$matches")
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            val detail = if (e is HermesRuntimeException) "${e.step}: ${e.message}" else e.message.orEmpty()
            report.addStep(
                DiagnosticStep(
                    "session_resume", StepStatus.FAILED, duration, detail,
                    extra = mapOf("content_matches_original" to false),
                ),
            )
            logLine("[session_resume] FAILED (${duration}ms): $detail")
        }
    }

    fun verifyWal() = viewModelScope.launch {
        val start = System.currentTimeMillis()
        try {
            val result = WalChecker.check(getApplication())
            val duration = System.currentTimeMillis() - start
            val isWal = result.journalMode.lowercase() == "wal"
            report.addStep(
                DiagnosticStep(
                    "wal_check",
                    if (isWal) StepStatus.OK else StepStatus.FAILED,
                    duration,
                    detail = "wal_file=${result.walFilePresent} shm_file=${result.shmFilePresent}",
                    extra = mapOf("journal_mode" to result.journalMode),
                ),
            )
            logLine("[wal_check] journal_mode=${result.journalMode} (${duration}ms)")
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            report.addStep(
                DiagnosticStep(
                    "wal_check", StepStatus.FAILED, duration, e.message.orEmpty(),
                    extra = mapOf("journal_mode" to "other"),
                ),
            )
            logLine("[wal_check] FAILED (${duration}ms): ${e.message}")
        }
    }

    fun exportReport(onReady: (File) -> Unit) = viewModelScope.launch {
        val file = report.writeToDisk()
        logLine("[export] wrote ${file.absolutePath}")
        onReady(file)
    }

    /** Steps 3-12 in order (spec §4.4 point 13), always exporting the report at the end regardless
     * of where the chain stopped. */
    fun runFullTest(onReportReady: (File) -> Unit) = viewModelScope.launch {
        installRuntime().join()
        if (report.hasFailed()) return@launch exportAndFinish(onReportReady)

        verifyNativeExtensions().join()
        if (report.hasFailed()) return@launch exportAndFinish(onReportReady)

        startHermes().join()
        if (report.hasFailed()) return@launch exportAndFinish(onReportReady)

        testWebSocket().join()
        if (report.hasFailed()) return@launch exportAndFinish(onReportReady)

        testConversation().join()
        if (report.hasFailed()) return@launch exportAndFinish(onReportReady)

        killBackend().join()
        if (report.hasFailed()) return@launch exportAndFinish(onReportReady)

        restartBackend().join()
        if (report.hasFailed()) return@launch exportAndFinish(onReportReady)

        testResume().join()
        if (report.hasFailed()) return@launch exportAndFinish(onReportReady)

        verifyWal().join()
        exportAndFinish(onReportReady)
    }

    private fun exportAndFinish(onReportReady: (File) -> Unit) {
        val file = report.writeToDisk()
        logLine("[export] wrote ${file.absolutePath}")
        onReportReady(file)
    }
}
