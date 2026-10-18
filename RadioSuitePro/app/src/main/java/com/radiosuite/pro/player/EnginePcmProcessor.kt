package com.radiosuite.pro.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import com.radiosuite.pro.dsp.DspEngine
import java.nio.ByteOrder
import kotlin.math.ceil

class EnginePcmProcessor(private val engine: DspEngine) : BaseAudioProcessor() {
    private var resampleAccumulator = 0
    private var pcmOutput = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount !in 1..2 || inputAudioFormat.sampleRate <= 0) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val format = inputAudioFormat
        val inputByteCount = inputBuffer.remaining()
        val inputFrames = inputByteCount / format.bytesPerFrame
        val capacity = ceil(inputFrames * OUTPUT_SAMPLE_RATE.toDouble() / format.sampleRate)
            .toInt() + 2
        if (pcmOutput.size < capacity * OUTPUT_CHANNELS) {
            pcmOutput = FloatArray(capacity * OUTPUT_CHANNELS)
        }

        val pcm = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val passthroughBuffer = inputBuffer.duplicate()
        val inputChannels = format.channelCount
        var outputFrames = 0
        repeat(inputFrames) {
            val left: Float
            val right: Float
            if (format.encoding == C.ENCODING_PCM_FLOAT) {
                left = pcm.float
                right = if (inputChannels > 1) pcm.float else left
            } else {
                left = pcm.short / 32768f
                right = if (inputChannels > 1) pcm.short / 32768f else left
            }

            resampleAccumulator += OUTPUT_SAMPLE_RATE
            while (resampleAccumulator >= format.sampleRate) {
                resampleAccumulator -= format.sampleRate
                val outputIndex = outputFrames * OUTPUT_CHANNELS
                pcmOutput[outputIndex] = left
                pcmOutput[outputIndex + 1] = right
                outputFrames++
            }
        }

        if (outputFrames > 0) engine.pushPlayerSamples(pcmOutput, OUTPUT_CHANNELS, outputFrames)

        val output = replaceOutputBuffer(inputByteCount)
        if (engine.isRendering) {
            repeat(inputByteCount) { output.put(0) }
        } else {
            output.put(passthroughBuffer)
        }
        output.flip()
    }

    override fun onFlush() {
        resampleAccumulator = 0
    }

    private companion object {
        const val OUTPUT_SAMPLE_RATE = 48000
        const val OUTPUT_CHANNELS = 2
    }
}