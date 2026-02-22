package com.bsuir.stmapp.viewmodel

import android.app.Application
import android.util.Log
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
    val errorMessage: String? = null,
    // Поля для dropdown с частотами мигания
    val selectedFrequency: Int = 1,  // Выбранная частота (1, 2, 5, 10 Гц)
    val availableFrequencies: List<Int> = listOf(1, 2, 5, 10),  // Доступные частоты
    val isButtonClicked: Boolean = false,  // Состояние кнопки
    // Поля для состояния LED
    val isBlinking: Boolean = false,  // Флаг мигания
    val blinkFrequency: Int = 0,  // Текущая частота мигания (0 = не мигает)
    // Поле для отображения сырого JSON ответа
    val rawJsonResponse: String = ""  // Последний JSON ответ от STM32
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UartRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "MainViewModel"
        private const val LED_ON_COMMAND = "{\"command\":\"led_on\"}\n"
        private const val LED_OFF_COMMAND = "{\"command\":\"led_off\"}\n"

        // Команды для мигания LED
        private const val FREQ_1HZ_COMMAND = "{\"command\":\"freq_1hz\"}\n"
        private const val FREQ_2HZ_COMMAND = "{\"command\":\"freq_2hz\"}\n"
        private const val FREQ_5HZ_COMMAND = "{\"command\":\"freq_5hz\"}\n"
        private const val FREQ_10HZ_COMMAND = "{\"command\":\"freq_10hz\"}\n"
        private const val STOP_BLINK_COMMAND = "{\"command\":\"stop_blink\"}\n"
        private const val GET_STATUS_COMMAND = "{\"command\":\"get_status\"}\n"
    }

    fun connectToDevice() {
        viewModelScope.launch {
            Log.d(TAG, "Начало подключения к устройству")
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val result = repository.connectToDevice()

            result.fold(
                onSuccess = { message ->
                    Log.i(TAG, "Устройство подключено: $message")
                    _uiState.value = _uiState.value.copy(
                        isConnected = true,
                        statusMessage = message,
                        isLoading = false
                    )
                },
                onFailure = { exception ->
                    Log.e(TAG, "Ошибка подключения: ${exception.message}", exception)
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
            Log.d(TAG, "Начало отключения от устройства")
            _uiState.value = _uiState.value.copy(isLoading = true)

            val result = repository.disconnect()

            result.fold(
                onSuccess = { message ->
                    Log.i(TAG, "Устройство отключено: $message")
                    _uiState.value = UiState(
                        isConnected = false,
                        isLedOn = false,
                        isBlinking = false,
                        blinkFrequency = 0,
                        statusMessage = message,
                        isLoading = false
                    )
                },
                onFailure = { exception ->
                    Log.e(TAG, "Ошибка отключения: ${exception.message}", exception)
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
            Log.w(TAG, "Попытка переключения LED при отключенном устройстве")
            _uiState.value = _uiState.value.copy(
                errorMessage = "Устройство не подключено"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val newLedState = !_uiState.value.isLedOn
            val command = if (newLedState) LED_ON_COMMAND else LED_OFF_COMMAND

            Log.d(TAG, "Переключение LED: ${if (newLedState) "ON" else "OFF"}")

            val result = repository.sendCommand(command)

            result.fold(
                onSuccess = { response ->
                    Log.i(TAG, "LED переключен успешно: status=${response.status}, led=${response.led}")

                    val rawJson = "{\"status\":\"${response.status}\",\"led\":\"${response.led}\",\"frequency\":\"${response.frequency}\"}"
                    val statusMsg = when (response.led) {
                        "on" -> "LED включен"
                        "off" -> "LED выключен"
                        else -> "Unknown response"
                    }

                    _uiState.value = _uiState.value.copy(
                        isLedOn = response.led == "on",
                        isBlinking = false,
                        blinkFrequency = 0,
                        statusMessage = statusMsg,
                        rawJsonResponse = rawJson,
                        isLoading = false
                    )
                },
                onFailure = { exception ->
                    Log.e(TAG, "Ошибка переключения LED: ${exception.message}", exception)
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Ошибка отправки команды",
                        errorMessage = exception.message,
                        isLoading = false
                    )
                }
            )
        }
    }

    // Обновление выбранной частоты в dropdown
    fun updateSelectedFrequency(frequency: Int) {
        _uiState.value = _uiState.value.copy(
            selectedFrequency = frequency,
            statusMessage = "Выбрана частота: $frequency Гц"
        )
    }

    // Отправка выбранной частоты (запуск мигания)
    fun sendSelectedFrequency() {
        if (!_uiState.value.isConnected) {
            Log.w(TAG, "Попытка запуска мигания при отключенном устройстве")
            _uiState.value = _uiState.value.copy(
                errorMessage = "Устройство не подключено. Сначала подключитесь к устройству."
            )
            return
        }

        viewModelScope.launch {
            // Устанавливаем состояние загрузки и клика
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isButtonClicked = true,
                errorMessage = null
            )

            val selectedFrequency = _uiState.value.selectedFrequency

            // Формируем команду в зависимости от частоты
            val command = when (selectedFrequency) {
                1 -> FREQ_1HZ_COMMAND
                2 -> FREQ_2HZ_COMMAND
                5 -> FREQ_5HZ_COMMAND
                10 -> FREQ_10HZ_COMMAND
                else -> {
                    Log.e(TAG, "Недопустимая частота: $selectedFrequency")
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Недопустимая частота: $selectedFrequency",
                        isLoading = false,
                        isButtonClicked = false
                    )
                    return@launch
                }
            }

            Log.d(TAG, "Запуск мигания с частотой $selectedFrequency Гц")

            // Отправляем команду через репозиторий
            val result = repository.sendCommand(command)

            result.fold(
                onSuccess = { response ->
                    Log.i(TAG, "Мигание запущено: status=${response.status}, led=${response.led}, freq=${response.frequency}")

                    val rawJson = "{\"status\":\"${response.status}\",\"led\":\"${response.led}\",\"frequency\":\"${response.frequency}\"}"
                    val actualFreq = response.frequency.toIntOrNull() ?: selectedFrequency
                    val statusMsg = when {
                        response.led == "blinking" -> "LED мигает с частотой $actualFreq Гц"
                        response.status == "ok" -> "Команда выполнена: LED=${response.led}, Частота=${response.frequency} Гц"
                        else -> "Статус: ${response.status}"
                    }

                    _uiState.value = _uiState.value.copy(
                        isBlinking = response.led == "blinking",
                        blinkFrequency = actualFreq,
                        isLedOn = false,
                        statusMessage = statusMsg,
                        rawJsonResponse = rawJson,
                        isLoading = false,
                        isButtonClicked = false
                    )
                },
                onFailure = { exception ->
                    Log.e(TAG, "Ошибка запуска мигания: ${exception.message}", exception)
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Ошибка отправки частоты",
                        errorMessage = "Не удалось установить мигание: ${exception.message}",
                        isLoading = false,
                        isButtonClicked = false
                    )
                }
            )
        }
    }

    // Остановка мигания
    fun stopBlinking() {
        if (!_uiState.value.isConnected) {
            Log.w(TAG, "Попытка остановки мигания при отключенном устройстве")
            _uiState.value = _uiState.value.copy(
                errorMessage = "Устройство не подключено"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            Log.d(TAG, "Остановка мигания")

            val result = repository.sendCommand(STOP_BLINK_COMMAND)

            result.fold(
                onSuccess = { response ->
                    Log.i(TAG, "Мигание остановлено: status=${response.status}, led=${response.led}")

                    val rawJson = "{\"status\":\"${response.status}\",\"led\":\"${response.led}\",\"frequency\":\"${response.frequency}\"}"
                    val statusMsg = when {
                        response.led == "off" -> "Мигание остановлено, LED выключен"
                        response.status == "ok" -> "Команда выполнена: LED=${response.led}"
                        else -> "Статус: ${response.status}"
                    }

                    _uiState.value = _uiState.value.copy(
                        isBlinking = false,
                        blinkFrequency = 0,
                        isLedOn = response.led == "on",
                        statusMessage = statusMsg,
                        rawJsonResponse = rawJson,
                        isLoading = false
                    )
                },
                onFailure = { exception ->
                    Log.e(TAG, "Ошибка остановки мигания: ${exception.message}", exception)
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Ошибка остановки мигания",
                        errorMessage = exception.message,
                        isLoading = false
                    )
                }
            )
        }
    }

    // Запрос статуса
    fun getStatus() {
        if (!_uiState.value.isConnected) {
            Log.w(TAG, "Попытка получения статуса при отключенном устройстве")
            _uiState.value = _uiState.value.copy(
                errorMessage = "Устройство не подключено"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            Log.d(TAG, "Запрос статуса устройства")

            val result = repository.sendCommand(GET_STATUS_COMMAND)

            result.fold(
                onSuccess = { response ->
                    Log.i(TAG, "Статус получен: status=${response.status}, led=${response.led}, freq=${response.frequency}")

                    val rawJson = "{\"status\":\"${response.status}\",\"led\":\"${response.led}\",\"frequency\":\"${response.frequency}\"}"
                    val freq = response.frequency.toIntOrNull() ?: 0
                    val statusMsg = when {
                        response.led == "blinking" -> "LED мигает ($freq Гц)"
                        response.led == "on" -> "LED включен"
                        response.led == "off" -> "LED выключен"
                        else -> "Статус: ${response.status}, LED: ${response.led}, Частота: ${response.frequency}"
                    }

                    _uiState.value = _uiState.value.copy(
                        isBlinking = response.led == "blinking",
                        blinkFrequency = freq,
                        isLedOn = response.led == "on",
                        statusMessage = statusMsg,
                        rawJsonResponse = rawJson,
                        isLoading = false
                    )
                },
                onFailure = { exception ->
                    Log.e(TAG, "Ошибка получения статуса: ${exception.message}", exception)
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Ошибка получения статуса",
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