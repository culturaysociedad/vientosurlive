package com.radiosuite.pro.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.common.C
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.time.LocalTime
import java.util.Locale

class ClockAnnouncementDataSourceFactory(
    context: Context,
    private val synthesizer: ClockAnnouncementSynthesizer,
) : DataSource.Factory {
    private val fallbackFactory = DefaultDataSource.Factory(context.applicationContext)

    override fun createDataSource(): DataSource =
        ClockAnnouncementDataSource(fallbackFactory.createDataSource(), synthesizer)
}

class ClockAnnouncementSynthesizer(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val initialized = CompletableFuture<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableFuture<File>>()
    private val textToSpeech: TextToSpeech

    init {
        textToSpeech = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            initialized.completeExceptionally(IOException("No se pudo iniciar la síntesis de voz"))
            return
        }

        val languageStatus = textToSpeech.setLanguage(Locale.forLanguageTag("es-ES"))
        if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
            initialized.completeExceptionally(IOException("No hay una voz en español disponible"))
            return
        }

        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                pending[utteranceId]?.complete(
                    File(appContext.cacheDir, "$utteranceId.wav"),
                )
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                pending[utteranceId]?.completeExceptionally(
                    IOException("Falló la síntesis de la locución de hora"),
                )
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                pending[utteranceId]?.completeExceptionally(
                    IOException("Falló la síntesis de la locución de hora (código $errorCode)"),
                )
            }
        })
        initialized.complete(true)
    }

    fun synthesizeCurrentTime(): ByteArray {
        try {
            if (!initialized.get(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException("No se pudo iniciar la síntesis de voz")
            }
            val now = LocalTime.now()
            val phrase = "La hora actual es ${now.hour} horas y ${now.minute} minutos."
            val utteranceId = UUID.randomUUID().toString()
            val outputFile = File(appContext.cacheDir, "$utteranceId.wav")
            val result = CompletableFuture<File>()
            pending[utteranceId] = result

            if (textToSpeech.synthesizeToFile(phrase, Bundle(), outputFile, utteranceId) != TextToSpeech.SUCCESS) {
                pending.remove(utteranceId)
                throw IOException("El sintetizador rechazó la locución de hora")
            }

            return try {
                result.get(SYNTHESIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                outputFile.readBytes()
            } finally {
                pending.remove(utteranceId)
                outputFile.delete()
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("No se pudo generar la locución de hora", error)
        }
    }

    fun shutdown() {
        pending.values.forEach { it.completeExceptionally(IOException("El sintetizador se cerró")) }
        pending.clear()
        textToSpeech.shutdown()
    }

    private companion object {
        const val INIT_TIMEOUT_SECONDS = 20L
        const val SYNTHESIS_TIMEOUT_SECONDS = 45L
    }
}

private class ClockAnnouncementDataSource(
    private val fallback: DataSource,
    private val synthesizer: ClockAnnouncementSynthesizer,
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var requestedUri: Uri? = null
    private var audio: ByteArray? = null
    private var readPosition = 0
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var isClockRequest = false

    override fun addTransferListener(transferListener: TransferListener) {
        fallback.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        requestedUri = dataSpec.uri
        isClockRequest = dataSpec.uri.scheme == CLOCK_SCHEME
        if (!isClockRequest) return fallback.open(dataSpec)

        val synthesized = synthesizer.synthesizeCurrentTime()
        if (dataSpec.position > synthesized.size) {
            throw IOException("La posición solicitada supera el audio sintetizado")
        }
        readPosition = dataSpec.position.toInt()
        val availableBytes = synthesized.size - readPosition
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            availableBytes.toLong()
        } else {
            minOf(availableBytes.toLong(), dataSpec.length)
        }
        audio = synthesized
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!isClockRequest) return fallback.read(buffer, offset, length)
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytes = audio ?: throw IOException("No se generó el audio de la locución")
        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()
        System.arraycopy(bytes, readPosition, buffer, offset, bytesToRead)
        readPosition += bytesToRead
        bytesRemaining -= bytesToRead
        return bytesToRead
    }

    override fun getUri(): Uri? = if (isClockRequest) requestedUri else fallback.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (isClockRequest) emptyMap() else fallback.responseHeaders

    override fun close() {
        audio = null
        requestedUri = null
        readPosition = 0
        bytesRemaining = C.LENGTH_UNSET.toLong()
        isClockRequest = false
        fallback.close()
    }

    private companion object {
        const val CLOCK_SCHEME = "radiosuite-clock"
    }
}
