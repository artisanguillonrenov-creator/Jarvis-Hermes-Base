package com.williamguillon.cortana.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.williamguillon.cortana.runtime.HermesRuntimeException
import com.williamguillon.cortana.runtime.TermuxLikeHermesRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DashboardUiState {
    data class Loading(val status: String) : DashboardUiState()
    data class Ready(val url: String) : DashboardUiState()
    data class Error(val message: String) : DashboardUiState()
}

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val runtime = TermuxLikeHermesRuntime(app.applicationContext)

    private val _state = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading("Installation du runtime…"))
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        boot()
    }

    private fun boot() = viewModelScope.launch {
        try {
            _state.value = DashboardUiState.Loading("Installation du runtime…")
            runtime.installRuntime()

            _state.value = DashboardUiState.Loading("Vérification des extensions natives…")
            val healthcheck = runtime.verifyNativeExtensions()
            if (healthcheck.fatalFailureCount != 0) {
                val detail = healthcheck.results.filter { it.fatal && !it.ok }
                    .joinToString { "${it.module}: ${it.error}" }
                _state.value = DashboardUiState.Error("Extensions natives : $detail")
                return@launch
            }

            _state.value = DashboardUiState.Loading("Démarrage de Hermes…")
            // Not runtime.start(): that would redo installRuntime()/verifyNativeExtensions()
            // above from scratch (a second pip install, a second healthcheck run) since this
            // view model already did the gate itself, one step at a time, for the status text.
            runtime.startBackendProcess()
            _state.value = DashboardUiState.Ready(runtime.dashboardHttpUrl)
        } catch (e: Exception) {
            val detail = if (e is HermesRuntimeException) "${e.step}: ${e.message}" else e.message.orEmpty()
            _state.value = DashboardUiState.Error(detail)
        }
    }
}
