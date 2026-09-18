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
import androidx.compose.ui.platform.LocalContext
import com.williamguillon.cortana.config.ProviderConfigScreen
import com.williamguillon.cortana.runtime.RuntimePaths
import com.williamguillon.cortana.ui.TechnicalScreen

/** Single-activity spike: no navigation library, just a two-screen boolean per spec §4.4. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProofOfArchitectureRoot() }
    }
}

@Composable
private fun ProofOfArchitectureRoot() {
    val ctx = LocalContext.current
    val envAlreadyConfigured = remember { RuntimePaths.envFile(ctx).exists() }
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
