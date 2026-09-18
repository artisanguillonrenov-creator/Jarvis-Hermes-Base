package com.williamguillon.cortana

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.williamguillon.cortana.ui.DashboardScreen
import com.williamguillon.cortana.ui.TechnicalScreen

/**
 * Single-activity spike. The real UI is [DashboardScreen] — the WebView pointed at `hermes
 * dashboard` — per the "application installable" revision of the spec: no native
 * re-implementation of Hermes's screens. [TechnicalScreen] (the old §4.4 button-by-button
 * checklist and its JSON-RPC client) is kept only as an internal diagnostic tool, reachable from
 * [DashboardScreen]'s loading/error state — it is not part of the normal user flow anymore.
 *
 * Known limitation: [DashboardScreen] and [TechnicalScreen] each own a separate
 * `TermuxLikeHermesRuntime` instance (both view models survive in this Activity's
 * ViewModelStore once created), so starting the backend from both screens in the same session can
 * spawn two `hermes dashboard` processes. Not fixed here — this diagnostic screen is meant for use
 * when the main flow already failed to start one, not alongside a running one.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProofOfArchitectureRoot() }
    }
}

@Composable
private fun ProofOfArchitectureRoot() {
    var showDiagnostics by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface {
            if (showDiagnostics) {
                TechnicalScreen(onBack = { showDiagnostics = false })
            } else {
                DashboardScreen(onOpenDiagnostics = { showDiagnostics = true })
            }
        }
    }
}
