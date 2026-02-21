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
import org.json.JSONObject

data class StmResponse(
    val status: String,
    val led: String,
    val frequency: String
)

class UartRepository(private val context: Context) {
    private var serialPort: UsbSerialPort? = null
    private var connection: UsbSerialPort? = null

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
            val buffer = ByteArray(1024)
            val startTime = System.currentTimeMillis()
            val responseBuilder = StringBuilder()

            Log.d(TAG, "Ожидание ответа от STM32...")

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val port = serialPort ?: return@withContext Result.failure(
                    Exception("Устройство отключено")
                )

                try {
                    val bytesRead = port.read(buffer, 100)
                    if (bytesRead > 0) {
                        val chunk = String(buffer, 0, bytesRead)
                        responseBuilder.append(chunk)
                        Log.d(TAG, "Получено байт: $bytesRead, данные: $chunk")

                        // Проверяем, получили ли полный JSON (содержит } и \n)
                        val response = responseBuilder.toString()
                        if (response.contains("}")) {
                            Log.i(TAG, "Полный ответ получен: $response")
                            return@withContext Result.success(response.trim())
                        }
                    }
                } catch (e: Exception) {
                    // Игнорируем таймауты чтения, продолжаем попытки
                    delay(50)
                }

                delay(50)
            }

            if (responseBuilder.isNotEmpty()) {
                val partialResponse = responseBuilder.toString().trim()
                Log.w(TAG, "Таймаут, получен частичный ответ: $partialResponse")
                return@withContext Result.success(partialResponse)
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

            // Ищем JSON объект в строке
            val startIndex = jsonString.indexOf('{')
            val endIndex = jsonString.lastIndexOf('}')

            if (startIndex == -1 || endIndex == -1) {
                Log.w(TAG, "JSON объект не найден в ответе")
                return null
            }

            val jsonPart = jsonString.substring(startIndex, endIndex + 1)
            val json = JSONObject(jsonPart)

            val response = StmResponse(
                status = json.optString("status", "unknown"),
                led = json.optString("led", "unknown"),
                frequency = json.optString("frequency", "0")
            )

            Log.i(TAG, "JSON распарсен: status=${response.status}, led=${response.led}, freq=${response.frequency}")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга JSON: ${e.message}", e)
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

