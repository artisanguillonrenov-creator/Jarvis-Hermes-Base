package com.williamguillon.cortana.config

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.williamguillon.cortana.runtime.RuntimePaths

/**
 * First-launch (or technical-screen-accessible) provider setup. See
 * TemporaryPlainEnvProviderConfig.kt: this is explicitly provisional plaintext storage, not
 * final Cortana credential handling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderConfigScreen(onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf(ProviderKind.OPENAI) }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    val importEnvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching { TemporaryPlainEnvProviderConfigWriter.importFromSaf(ctx, uri) }
                .onSuccess { status = "Imported .env into ${RuntimePaths.envFile(ctx).absolutePath}" }
                .onFailure { status = "Import failed: ${it.message}" }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Provider configuration (spike — plaintext, provisional)")

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = provider.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Provider") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ProviderKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(kind.label) },
                        onClick = { provider = kind; expanded = false },
                    )
                }
            }
        }

        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it },
            label = { Text("Base URL (optional)") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it },
            label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it },
            label = { Text("Model") }, modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = {
            TemporaryPlainEnvProviderConfigWriter.write(
                ctx, TemporaryPlainEnvProviderConfig(provider, baseUrl, apiKey, model),
            )
            status = "Saved to ${RuntimePaths.envFile(ctx).absolutePath}"
            onSaved()
        }) { Text("Save") }

        Button(onClick = { importEnvLauncher.launch(arrayOf("text/plain", "*/*")) }) {
            Text("Importer un .env")
        }

        if (status.isNotBlank()) Text(status)
    }
}
