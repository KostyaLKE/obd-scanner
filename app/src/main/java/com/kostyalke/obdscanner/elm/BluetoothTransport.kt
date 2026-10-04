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

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        adapter.cancelDiscovery()
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
            val s = try {
                create()
            } catch (e: Exception) {
                lastError = e
                continue
            }
            try {
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                return@withContext
            } catch (e: IOException) {
                lastError = e
                runCatching { s.close() }
                delay(300)
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
        val inp = input ?: throw IOException("Нет соединения")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(256)
        while (true) {
            val available = inp.available()
            if (available > 0) {
                val n = inp.read(buf, 0, minOf(available, buf.size))
                for (i in 0 until n) {
                    val c = (buf[i].toInt() and 0xFF).toChar()
                    if (c == '>') return@withContext sb.toString()
                    if (c != '\u0000') sb.append(c)
                }
            } else {
                if (System.currentTimeMillis() > deadline) throw ElmTimeoutException(sb.toString())
                delay(5)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        sb.toString()
    }

    override suspend fun clearInput() = withContext(Dispatchers.IO) {
        val inp = input ?: return@withContext
        while (inp.available() > 0) inp.skip(inp.available().toLong())
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
