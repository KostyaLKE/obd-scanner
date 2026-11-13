package com.kostyalke.obdscanner.elm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Классический Bluetooth (SPP) — так работают почти все ELM327 v1.5/v1.6/v2.1. */
@SuppressLint("MissingPermission")
class BluetoothTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
) : ObdTransport {

    override val name: String = device.name ?: device.address

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    /** Сокет, который сейчас подключается: [close] должен прервать и его. */
    @Volatile private var connecting: BluetoothSocket? = null
    @Volatile private var closed = false
    private val reader = PromptReader()

    override suspend fun open() {
        // Остановить поиск устройств (он тормозит соединение). На Android 12+ для этого нужно
        // отдельное разрешение BLUETOOTH_SCAN, которого у нас нет — тогда просто продолжаем.
        try {
            adapter.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        var lastError: Exception? = null
        // Китайские клоны капризны: пробуем по очереди три способа подключения.
        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        for (create in attempts) {
            if (closed) throw IOException("Подключение отменено")
            val s = try {
                create()
            } catch (e: Exception) {
                lastError = e
                continue
            }
            connecting = s
            try {
                // connect() блокирует поток; прервать его можно только закрытием ЭТОГО сокета.
                abortable(CONNECT_TIMEOUT_MS, abort = { catching { s.close() } }) { s.connect() }
                if (closed) { catching { s.close() }; throw IOException("Подключение отменено") }
                socket = s
                input = s.inputStream
                output = s.outputStream
                return
            } catch (e: IOException) {
                lastError = e
                catching { s.close() }
                delay(300)
            } finally {
                connecting = null
            }
        }
        throw IOException(
            "Не удалось подключиться к «$name». Проверьте, что зажигание включено, " +
                "адаптер вставлен в разъём и не подключён к другому приложению.",
            lastError,
        )
    }

    override suspend fun write(text: String) = withContext(Dispatchers.IO) {
        val out = output ?: throw IOException("Нет соединения")
        out.write(text.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String = withContext(Dispatchers.IO) {
        reader.read(input ?: throw IOException("Нет соединения"), timeoutMs)
    }

    override suspend fun clearInput(): String = withContext(Dispatchers.IO) {
        input?.let { reader.clear(it) } ?: ""
    }

    override fun close() {
        closed = true
        connecting?.let { catching { it.close() } }
        catching { input?.close() }
        catching { output?.close() }
        catching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val CONNECT_TIMEOUT_MS = 12_000L
    }
}
