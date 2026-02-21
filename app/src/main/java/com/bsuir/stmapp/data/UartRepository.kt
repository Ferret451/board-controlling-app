package com.bsuir.stmapp.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UartRepository(private val context: Context) {
    private var serialPort: UsbSerialPort? = null
    private var connection: UsbSerialPort? = null

    companion object {
        private const val BAUD_RATE = 115200
        private const val DATA_BITS = 8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE
        const val ACTION_USB_PERMISSION = "com.bsuir.stmapp.USB_PERMISSION"
    }

    suspend fun connectToDevice(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

            if (availableDrivers.isEmpty()) {
                return@withContext Result.failure(Exception("Не найдены USB устройства"))
            }

            val driver = availableDrivers[0]
            val usbConnection = usbManager.openDevice(driver.device)

            if (usbConnection == null) {
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

            connection = serialPort

            Result.success("Подключено к ${driver.device.deviceName}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val port = serialPort ?: return@withContext Result.failure(
                Exception("Устройство не подключено")
            )

            val data = command.toByteArray()
            port.write(data, 1000)

            Result.success("Команда отправлена: $command")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun disconnect(): Result<String> = withContext(Dispatchers.IO) {
        try {
            serialPort?.close()
            serialPort = null
            connection = null
            Result.success("Отключено")
        } catch (e: Exception) {
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

