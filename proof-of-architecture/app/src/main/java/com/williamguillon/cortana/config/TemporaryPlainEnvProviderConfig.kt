package com.williamguillon.cortana.config

import android.content.Context
import com.williamguillon.cortana.runtime.RuntimePaths
import java.io.File

/**
 * PROVISOIRE (spec §4.2): this writes provider credentials to a plaintext file on internal
 * storage. It exists only so this spike can talk to a real model provider without a PC/ADB.
 * It will be replaced by a real `agent/vault_backends/android_keystore.py` backend in a
 * dedicated security chantier — do not build anything else on top of this class.
 */
enum class ProviderKind(val label: String, val apiKeyEnvVar: String, val baseUrlEnvVar: String) {
    // Env var names verified against hermes_cli/auth.py PROVIDER_REGISTRY and
    // hermes_cli/config_defaults.py OPTIONAL_ENV_VARS — not guessed.
    OPENAI("OpenAI", "OPENAI_API_KEY", "OPENAI_BASE_URL"),
    ANTHROPIC("Anthropic", "ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL"),
    // OpenRouter is OpenAI-compatible and is dispatched through OPENAI_BASE_URL when active
    // (see hermes_cli/main.py: `if active == "openrouter" and get_env_value("OPENAI_BASE_URL")`).
    OPENROUTER("OpenRouter", "OPENROUTER_API_KEY", "OPENAI_BASE_URL"),
    CUSTOM("Custom (OpenAI-compatible)", "OPENAI_API_KEY", "OPENAI_BASE_URL"),
}

data class TemporaryPlainEnvProviderConfig(
    val provider: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

object TemporaryPlainEnvProviderConfigWriter {

    /** Writes the form fields as `HERMES_HOME/.env` lines. Overwrites any existing file. */
    fun write(ctx: Context, config: TemporaryPlainEnvProviderConfig) {
        val hermesHome = RuntimePaths.hermesHome(ctx)
        hermesHome.mkdirs()
        val lines = buildList {
            if (config.apiKey.isNotBlank()) add("${config.provider.apiKeyEnvVar}=${config.apiKey}")
            if (config.baseUrl.isNotBlank()) add("${config.provider.baseUrlEnvVar}=${config.baseUrl}")
            if (config.model.isNotBlank()) {
                // HERMES_MODEL / HERMES_INFERENCE_MODEL pairing verified in
                // hermes_cli/main_tui_launch.py.
                add("HERMES_MODEL=${config.model}")
                add("HERMES_INFERENCE_MODEL=${config.model}")
            }
        }
        RuntimePaths.envFile(ctx).writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /**
     * Copies a SAF-picked `.env` file's bytes verbatim into `HERMES_HOME/.env`, once, read via
     * ACTION_OPEN_DOCUMENT. The SAF Uri itself is never retained (rapport d'architecture: rien de
     * critique ne doit vivre sur un chemin SAF).
     */
    fun importFromSaf(ctx: Context, sourceUri: android.net.Uri) {
        val hermesHome = RuntimePaths.hermesHome(ctx)
        hermesHome.mkdirs()
        val dest: File = RuntimePaths.envFile(ctx)
        ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open the selected .env file")
    }
}
