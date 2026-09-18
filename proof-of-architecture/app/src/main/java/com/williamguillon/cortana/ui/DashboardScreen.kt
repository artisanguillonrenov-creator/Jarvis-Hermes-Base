package com.williamguillon.cortana.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Phase 1's single screen (per the "application installable" revision): a status line while
 * `hermes dashboard` boots, then the real Hermes web dashboard — StatusPage, ConfigPage, EnvPage,
 * ChatPage — rendered in a plain platform WebView. No native re-implementation of any of those
 * pages; Cortana's job here is just "run the real engine and give it a window."
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DashboardScreen(onOpenDiagnostics: () -> Unit) {
    val vm: DashboardViewModel = viewModel()
    val state by vm.state.collectAsState()

    when (val current = state) {
        is DashboardUiState.Loading, is DashboardUiState.Error -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    when (current) {
                        is DashboardUiState.Loading -> current.status
                        is DashboardUiState.Error -> "Erreur: ${current.message}"
                        else -> ""
                    },
                )
                // Diagnostics only offered before the real dashboard is up — once it loads, this
                // screen stays a single undecorated surface (spec: "un seul écran suffit").
                TextButton(onClick = onOpenDiagnostics, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Diagnostics avancés")
                }
            }
        }
        is DashboardUiState.Ready -> {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        loadUrl(current.url)
                    }
                },
                update = { webView -> if (webView.url != current.url) webView.loadUrl(current.url) },
            )
        }
    }
}
