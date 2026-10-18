package com.radiosuite.pro.stream

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Cliente de emisión Icecast v2 (HTTP PUT con chunked-transfer-encoding).
 *
 * Abre una conexión persistente y escribe continuamente los paquetes
 * codificados (Opus/Vorbis/MP3) entregados por el [StreamEncoder] nativo.
 * Incluye reconexión automática exponencial ante fallos de red.
 *
 * No realiza codificación: solo transporte. La codificación vive en C++.
 */
class IcecastClient(
    private val host: String,
    private val port: Int,
    private val mount: String,
    private val password: String,
    private val codec: StreamCodec,
    private val bitrate: Int,
) {
    @Volatile private var connected = false
    @Volatile private var shouldRun = false
    @Volatile var lastError: String = ""
        private set
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Inicia el bucle de emisión en segundo plano. */
    fun start() {
        shouldRun = true
        thread(name = "IcecastClient", isDaemon = true) { runLoop() }
    }

    fun stop() {
        shouldRun = false
        closeConnection()
    }

    fun isConnected(): Boolean = connected

    /** Envía un paquete codificado al servidor. No bloqueante si la cola admite. */
    @Synchronized
    fun sendPacket(data: ByteArray) {
        if (!connected) return
        try {
            out?.apply {
                write(data)
                flush()
            }
        } catch (e: IOException) {
            connected = false
        }
    }

    private fun runLoop() {
        var backoffMs = 1000L
        while (shouldRun) {
            try {
                connect()
                backoffMs = 1000L
                // Mantenemos viva la conexión; los paquetes llegan vía sendPacket().
                while (shouldRun && connected) {
                    Thread.sleep(50)
                }
            } catch (e: IOException) {
                connected = false
                lastError = e.message ?: "No se pudo conectar con Icecast"
                closeConnection()
            }
            if (shouldRun) {
                Thread.sleep(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L) // backoff exponencial, máx 30s
            }
        }
    }

    private fun connect() {
        val s = Socket(host, port)
        s.soTimeout = 10_000
        s.tcpNoDelay = true
        s.keepAlive = true
        socket = s
        out = s.getOutputStream()
        val contentType = when (codec) {
            StreamCodec.Opus   -> "application/ogg"
            StreamCodec.Vorbis -> "application/ogg"
            StreamCodec.Mp3    -> "audio/mpeg"
        }
        val request = "SOURCE ${mount} HTTP/1.1\r\n" +
            "Host: ${host}:${port}\r\n" +
            "Authorization: Basic ${java.util.Base64.getEncoder().encodeToString("source:${password}".toByteArray())}\r\n" +
            "Content-Type: ${contentType}\r\n" +
            "ice-bitrate: ${bitrate}\r\n" +
            "User-Agent: RadioSuitePro/1.0\r\n" +
            "\r\n"
        out?.apply {
            write(request.toByteArray())
            flush()
        }
        val response = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII)).readLine()
            ?: throw IOException("Icecast cerró la conexión sin responder")
        val statusCode = response.split(' ').getOrNull(1)?.toIntOrNull()
        if (statusCode != 200) throw IOException("Icecast respondió: $response")
        s.soTimeout = 0
        lastError = ""
        connected = true
    }

    @Synchronized
    private fun closeConnection() {
        try { out?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        out = null
        socket = null
    }
}
