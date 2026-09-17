package com.cortana.proofofarchitecture.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class NativeCheckResult(
    val module: String,
    val ok: Boolean,
    val fatal: Boolean,
    val error: String?,
)

data class NativeHealthcheckResult(
    val results: List<NativeCheckResult>,
    val fatalFailureCount: Int,
    val rawStdout: String,
    val exitCode: Int,
)

/**
 * Runs assets/healthcheck.py (§4.3 of the spec) through the just-installed interpreter and never
 * treats a bare `pip install` exit code as success on its own. `uvloop`/`httptools` failing is
 * NOT fatal (optional uvicorn accelerators); sqlite3/pydantic_core/cryptography/psutil/websockets/
 * httpx+certifi failing IS fatal and must block `hermes serve` from being started at all.
 */
object NativeHealthcheck {

    fun run(pythonBinary: File, scriptFile: File): NativeHealthcheckResult {
        val process = ProcessBuilder(pythonBinary.absolutePath, scriptFile.absolutePath)
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        val exitCode = if (finished) process.exitValue() else {
            process.destroyForcibly()
            -1
        }

        val jsonLine = stdout.lines().lastOrNull { it.trim().startsWith("{") }
            ?: return NativeHealthcheckResult(
                results = emptyList(),
                fatalFailureCount = -1,
                rawStdout = if (stdout.isNotBlank()) stdout else stderr,
                exitCode = exitCode,
            )

        val parsed = JSONObject(jsonLine)
        val resultsArray: JSONArray = parsed.getJSONArray("results")
        val results = (0 until resultsArray.length()).map { i ->
            val entry = resultsArray.getJSONObject(i)
            NativeCheckResult(
                module = entry.getString("module"),
                ok = entry.getBoolean("ok"),
                fatal = entry.getBoolean("fatal"),
                error = entry.optString("error", null),
            )
        }
        return NativeHealthcheckResult(
            results = results,
            fatalFailureCount = parsed.getInt("fatal_failure_count"),
            rawStdout = stdout,
            exitCode = exitCode,
        )
    }
}
