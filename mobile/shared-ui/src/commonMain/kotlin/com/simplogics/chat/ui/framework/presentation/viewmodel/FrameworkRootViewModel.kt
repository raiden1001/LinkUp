package com.simplogics.chat.ui.framework.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplogics.chat.ui.framework.domain.model.UiResult
import com.simplogics.chat.ui.framework.domain.usecase.GetFrameworkBootstrapUseCase
import com.simplogics.chat.ui.framework.presentation.state.FrameworkUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FrameworkRootViewModel(
    private val getFrameworkBootstrapUseCase: GetFrameworkBootstrapUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FrameworkUiState())
    val uiState: StateFlow<FrameworkUiState> = _uiState.asStateFlow()

    init {
        loadBootstrapData()
    }

    private fun loadBootstrapData() {
        viewModelScope.launch {
            when (val result = getFrameworkBootstrapUseCase()) {
                is UiResult.Success -> {
                    _uiState.update { current -> current.copy(subtitle = result.data) }
                }

                is UiResult.Error -> {
                    _uiState.update { current ->
                        current.copy(subtitle = "Framework initialization failed: ${result.message}")
                    }
                }
            }
        }
    }
}
