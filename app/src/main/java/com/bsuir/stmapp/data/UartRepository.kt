package com.bsuir.stmapp.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

@Serializable
data class StmResponse(
    val status: String,
    val led: String,
    val frequency: String
)

class UartRepository(private val context: Context) {
    private var serialPort: UsbSerialPort? = null
    private var connection: UsbSerialPort? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    companion object {
        private const val TAG = "UartRepository"
        private const val BAUD_RATE = 115200
        private const val DATA_BITS = 8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE
        const val ACTION_USB_PERMISSION = "com.bsuir.stmapp.USB_PERMISSION"
    }

    suspend fun connectToDevice(): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Попытка подключения к USB устройству...")
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

            if (availableDrivers.isEmpty()) {
                Log.e(TAG, "USB устройства не найдены")
                return@withContext Result.failure(Exception("Не найдены USB устройства"))
            }

            val driver = availableDrivers[0]
            Log.d(TAG, "Найдено устройство: ${driver.device.deviceName}")

            val usbConnection = usbManager.openDevice(driver.device)

            if (usbConnection == null) {
                Log.w(TAG, "Требуется разрешение на доступ к USB")
                // Запрос разрешения на доступ к USB устройству
                val permissionIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE
                )
                usbManager.requestPermission(driver.device, permissionIntent)
                return@withContext Result.failure(Exception("Требуется разрешение на доступ к USB"))
            }

            serialPort = driver.ports[0]
            serialPort?.open(usbConnection)
            serialPort?.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)

            Log.d(TAG, "Настройки UART: $BAUD_RATE бод, $DATA_BITS бит данных")

            connection = serialPort

            val successMessage = "Подключено к ${driver.device.deviceName}"
            Log.i(TAG, successMessage)
            Result.success(successMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка подключения: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun readResponse(timeoutMs: Long = 2000): Result<String> = withContext(Dispatchers.IO) {
        try {
            val buffer = ByteArray(256)
            val startTime = System.currentTimeMillis()
            val jsonBuilder = StringBuilder()
            var depth = 0        // глубина вложенности { }
            var jsonStarted = false

            Log.d(TAG, "Ожидание ответа от STM32...")

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val port = serialPort ?: return@withContext Result.failure(
                    Exception("Устройство отключено")
                )

                try {
                    val bytesRead = port.read(buffer, 100)
                    if (bytesRead > 0) {
                        val chunk = String(buffer, 0, bytesRead, Charsets.US_ASCII)
                        Log.d(TAG, "Получено байт: $bytesRead, данные: $chunk")

                        for (ch in chunk) {
                            when {
                                ch == '{' -> {
                                    jsonStarted = true
                                    depth++
                                    jsonBuilder.append(ch)
                                }
                                jsonStarted && ch == '}' -> {
                                    jsonBuilder.append(ch)
                                    depth--
                                    if (depth == 0) {
                                        // Получили полный JSON объект
                                        val result = jsonBuilder.toString()
                                        Log.i(TAG, "Полный JSON получен: $result")
                                        return@withContext Result.success(result)
                                    }
                                }
                                jsonStarted -> jsonBuilder.append(ch)
                            }
                        }
                    }
                } catch (e: Exception) {
                    delay(20)
                }

                delay(20)
            }

            if (jsonBuilder.isNotEmpty()) {
                val partial = jsonBuilder.toString()
                Log.w(TAG, "Таймаут, частичный JSON: $partial")
                return@withContext Result.success(partial)
            }

            Log.w(TAG, "Таймаут ожидания ответа")
            Result.failure(Exception("Нет ответа от устройства"))
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения ответа: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseStmResponse(jsonString: String): StmResponse? {
        return try {
            Log.d(TAG, "Парсинг JSON: $jsonString")
            val response = json.decodeFromString<StmResponse>(jsonString.trim())
            Log.i(TAG, "JSON распарсен: status=${response.status}, led=${response.led}, freq=${response.frequency}")
            response
        } catch (e: SerializationException) {
            Log.e(TAG, "Ошибка сериализации JSON: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга: ${e.message}", e)
            null
        }
    }

    suspend fun sendCommand(command: String): Result<StmResponse> = withContext(Dispatchers.IO) {
        try {
            val port = serialPort ?: return@withContext Result.failure(
                Exception("Устройство не подключено")
            )

            Log.d(TAG, "Отправка команды: $command")
            val data = command.toByteArray()
            port.write(data, 1000)
            Log.i(TAG, "Команда отправлена успешно")

            // Ждем и читаем ответ
            val readResult = readResponse()

            readResult.fold(
                onSuccess = { response ->
                    val stmResponse = parseStmResponse(response)
                    if (stmResponse != null) {
                        Log.i(TAG, "Ответ обработан успешно")
                        Result.success(stmResponse)
                    } else {
                        Log.w(TAG, "Не удалось распарсить ответ, используем сырые данные")
                        // Возвращаем базовый ответ с сырыми данными
                        Result.success(StmResponse("ok", response, "0"))
                    }
                },
                onFailure = { exception ->
                    Log.e(TAG, "Ошибка чтения ответа: ${exception.message}")
                    Result.failure(exception)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки команды: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun disconnect(): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Отключение от устройства...")
            serialPort?.close()
            serialPort = null
            connection = null
            Log.i(TAG, "Устройство успешно отключено")
            Result.success("Отключено")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отключения: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun isConnected(): Boolean = serialPort != null && connection != null

    suspend fun getAvailableDevices(): List<UsbDevice> = withContext(Dispatchers.IO) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        availableDrivers.map { it.device }
    }
}

