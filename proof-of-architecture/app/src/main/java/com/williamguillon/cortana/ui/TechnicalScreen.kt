package com.williamguillon.cortana.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.williamguillon.cortana.diagnostics.DiagnosticExporter

/**
 * Buttons only, in spec order (§4.4). Deliberately ugly (spec §0: "L'UI peut être laide" —
 * this is a measurement spike, not production Cortana UI). Internal diagnostic tool only, kept
 * for testing the runtime/gateway independently of the real dashboard — see [DashboardScreen] for
 * the normal user flow. Provider/API-key configuration is no longer done here: Hermes's own web
 * dashboard already has a full EnvPage for that (`web/src/pages/EnvPage.tsx`), so a native
 * Kotlin provider form here would just duplicate it.
 */
@Composable
fun TechnicalScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm: TechnicalViewModel = viewModel()
    val status by vm.status.collectAsState()
    val log by vm.log.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onBack) { Text("← Retour au dashboard") }
        Divider(Modifier.padding(vertical = 8.dp))

        Text("2. Runtime status: $status")
        Divider(Modifier.padding(vertical = 8.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(onClick = { vm.installRuntime() }) { Text("3. Installer runtime") }
            Button(onClick = { vm.verifyNativeExtensions() }) { Text("4. Vérifier extensions natives") }
            Button(onClick = { vm.startHermes() }) { Text("5. Démarrer Hermes") }
            Button(onClick = { vm.testWebSocket() }) { Text("6. Tester WebSocket") }
            Button(onClick = { vm.testConversation() }) { Text("7. Tester conversation") }
            Button(onClick = { vm.killBackend() }) { Text("8. Kill Hermes backend") }
            Button(onClick = { vm.restartBackend() }) { Text("9. Redémarrer") }
            Button(onClick = { vm.testResume() }) { Text("10. Tester reprise") }
            Button(onClick = { vm.verifyWal() }) { Text("11. Vérifier SQLite/WAL") }
            Button(onClick = {
                vm.exportReport { file -> DiagnosticExporter.share(ctx, file) }
            }) { Text("12. Exporter le rapport de diagnostic") }
            Button(onClick = {
                vm.runFullTest { file -> DiagnosticExporter.share(ctx, file) }
            }) { Text("13. Run full test") }
        }

        Divider(Modifier.padding(vertical = 8.dp))
        Text("Log:")
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(log) { line -> Text(line) }
        }
    }
}
