package com.bsuir.stmapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bsuir.stmapp.data.UartRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val isConnected: Boolean = false,
    val isLedOn: Boolean = false,
    val statusMessage: String = "Не подключено",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UartRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    companion object {
        private const val LED_ON_COMMAND = "{\"command\":\"led_on\"}\n"
        private const val LED_OFF_COMMAND = "{\"command\":\"led_off\"}\n"
    }

    fun connectToDevice() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val result = repository.connectToDevice()

            result.fold(
                onSuccess = { message ->
                    _uiState.value = _uiState.value.copy(
                        isConnected = true,
                        statusMessage = message,
                        isLoading = false
                    )
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        isConnected = false,
                        statusMessage = "Ошибка подключения",
                        errorMessage = exception.message,
                        isLoading = false
                    )
                }
            )
        }
    }

    fun disconnectFromDevice() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val result = repository.disconnect()

            result.fold(
                onSuccess = { message ->
                    _uiState.value = UiState(
                        isConnected = false,
                        isLedOn = false,
                        statusMessage = message,
                        isLoading = false
                    )
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = exception.message,
                        isLoading = false
                    )
                }
            )
        }
    }

    fun toggleLed() {
        if (!_uiState.value.isConnected) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Устройство не подключено"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val newLedState = !_uiState.value.isLedOn
            val command = if (newLedState) LED_ON_COMMAND else LED_OFF_COMMAND

            val result = repository.sendCommand(command)

            result.fold(
                onSuccess = { message ->
                    _uiState.value = _uiState.value.copy(
                        isLedOn = newLedState,
                        statusMessage = if (newLedState) "Светодиод включен" else "Светодиод выключен",
                        isLoading = false
                    )
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Ошибка отправки команды",
                        errorMessage = exception.message,
                        isLoading = false
                    )
                }
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            repository.disconnect()
        }
    }
}

