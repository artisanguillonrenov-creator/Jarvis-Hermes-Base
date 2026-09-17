package com.cortana.proofofarchitecture

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
import com.cortana.proofofarchitecture.config.ProviderConfigScreen
import com.cortana.proofofarchitecture.runtime.RuntimePaths
import com.cortana.proofofarchitecture.ui.TechnicalScreen

/** Single-activity spike: no navigation library, just a two-screen boolean per spec §4.4. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProofOfArchitectureRoot() }
    }
}

@Composable
private fun ProofOfArchitectureRoot() {
    val envAlreadyConfigured = remember { RuntimePaths.envFile(androidx.compose.ui.platform.LocalContext.current).exists() }
    var showProviderConfig by remember { mutableStateOf(!envAlreadyConfigured) }

    MaterialTheme {
        Surface {
            if (showProviderConfig) {
                ProviderConfigScreen(onSaved = { showProviderConfig = false })
            } else {
                TechnicalScreen(onOpenProviderConfig = { showProviderConfig = true })
            }
        }
    }
}
