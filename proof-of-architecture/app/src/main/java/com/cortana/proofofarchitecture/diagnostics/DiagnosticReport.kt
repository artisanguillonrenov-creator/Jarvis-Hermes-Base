package com.cortana.proofofarchitecture.diagnostics

import android.content.Context
import android.os.Build
import com.cortana.proofofarchitecture.runtime.NativeHealthcheckResult
import com.cortana.proofofarchitecture.runtime.RuntimePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class StepStatus { OK, FAILED, SKIPPED }

data class DiagnosticStep(
    val name: String,
    val status: StepStatus,
    val durationMs: Long,
    val detail: String = "",
    val extra: Map<String, Any?> = emptyMap(),
)

/**
 * Mirrors the JSON schema of spec §4.5 exactly (field names included) so the report is directly
 * usable by William/ChatGPT without translation.
 */
class DiagnosticReportBuilder(private val ctx: Context) {
    private val steps = mutableListOf<DiagnosticStep>()
    private var nativeHealthcheck: NativeHealthcheckResult? = null

    fun setNativeHealthcheck(result: NativeHealthcheckResult) {
        nativeHealthcheck = result
    }

    fun addStep(step: DiagnosticStep) {
        steps += step
    }

    /** Used by the "Run full test" orchestration to stop at the first fatal failure (spec §4.4 point 13). */
    fun hasFailed(): Boolean = steps.any { it.status == StepStatus.FAILED }

    fun build(): JSONObject {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val firstFailure = steps.firstOrNull { it.status == StepStatus.FAILED }?.name

        val healthcheckJson = JSONObject()
        val hc = nativeHealthcheck
        if (hc != null) {
            val resultsArray = JSONArray()
            hc.results.forEach { r ->
                resultsArray.put(
                    JSONObject().put("module", r.module).put("ok", r.ok).put("fatal", r.fatal)
                        .apply { if (r.error != null) put("error", r.error) },
                )
            }
            healthcheckJson.put("results", resultsArray)
            healthcheckJson.put("fatal_failure_count", hc.fatalFailureCount)
        } else {
            healthcheckJson.put("results", JSONArray())
            healthcheckJson.put("fatal_failure_count", -1)
        }

        val stepsArray = JSONArray()
        steps.forEach { s ->
            val stepJson = JSONObject()
                .put("name", s.name)
                .put("status", s.status.name.lowercase())
                .put("duration_ms", s.durationMs)
                .put("detail", s.detail)
            s.extra.forEach { (k, v) -> stepJson.put(k, v) }
            stepsArray.put(stepJson)
        }

        return JSONObject()
            .put("generated_at", isoFormat.format(Date()))
            .put(
                "device",
                JSONObject()
                    .put("model", Build.MODEL)
                    .put("android_version", Build.VERSION.RELEASE)
                    .put("sdk_int", Build.VERSION.SDK_INT)
                    .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
            )
            .put("native_healthcheck", healthcheckJson)
            .put("steps", stepsArray)
            .put("overall", if (firstFailure == null && steps.isNotEmpty()) "pass" else "fail")
            .put("first_failure", firstFailure)
    }

    fun writeToDisk(): File {
        val dir = RuntimePaths.diagnosticsDir(ctx)
        dir.mkdirs()
        val file = File(dir, "diagnostic-${System.currentTimeMillis()}.json")
        file.writeText(build().toString(2))
        return file
    }
}
