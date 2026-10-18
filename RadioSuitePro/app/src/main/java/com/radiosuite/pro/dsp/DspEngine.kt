package com.radiosuite.pro.dsp

/**
 * Puente JNI sobre el motor DSP nativo (C++/NDK).
 *
 * Cada instancia posee un handle nativo (long) que identifica al [NativeEngine]
 * C++ subyacente: mezclador + pipeline DSP + render Oboe + codificador.
 *
 * Thread-safety: los métodos de parámetros son seguros desde cualquier hilo;
 * los de métricas están pensados para polling desde la UI (StateFlow).
 */
class DspEngine {

    private var handle: Long = 0L
    private var released = false
    @Volatile var isRendering: Boolean = false
        private set
    internal val nativeHandle: Long get() = handle

    init {
        handle = nativeCreate()
        require(handle != 0L) { "No se pudo inicializar el motor DSP nativo" }
    }

    /** Inicia el render Oboe + pipeline. */
    fun start(): Boolean {
        isRendering = nativeStart(handle)
        return isRendering
    }

    /** Detiene el render y libera el stream Oboe. */
    fun stop() {
        isRendering = false
        nativeStop(handle)
    }

    /** Publica una nueva instantánea de parámetros del pipeline. */
    fun updatePipeline(params: DspParameters) {
        nativeUpdatePipeline(
            handle,
            params.agcTargetDb,
            params.agcMaxGainDb,
            params.agcAttackMs,
            params.agcReleaseMs,
            params.outputGainDb,
            params.enabled
        )
    }

    fun setSourceGain(sourceId: Int, gainDb: Float) {
        if (handle != 0L) nativeSetSourceGain(handle, sourceId, gainDb.coerceIn(-60f, 6f))
    }

    fun pushInputSamples(samples: FloatArray, channels: Int = 1, frames: Int = samples.size / channels) {
        if (samples.isNotEmpty() && handle != 0L) {
            nativePushInputPcm(handle, samples, channels, frames)
        }
    }

    fun pushPlayerSamples(samples: FloatArray, channels: Int, frames: Int = samples.size / channels) {
        if (samples.isNotEmpty() && handle != 0L) {
            nativePushPlayerPcm(handle, samples, channels, frames)
        }
    }

    // ---- Métricas para vómetros en tiempo real ----
    fun inputPeakDb(): Float  = nativeInputPeakDb(handle)
    fun outputPeakDb(): Float = nativeOutputPeakDb(handle)
    fun outputRmsDb(): Float  = nativeOutputRmsDb(handle)
    fun agcGainDb(): Float    = nativeAgcGainDb(handle)
    fun brickwallGrDb(): Float = nativeBrickwallGrDb(handle)

    @Synchronized
    fun release() {
        if (!released && handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
            released = true
        }
    }

    protected fun finalize() = release()

    companion object {
        init {
            System.loadLibrary("radiosuite-native")
        }

        // ---- Declaraciones nativas (jni_bridge.cpp) ----
        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeStart(handle: Long): Boolean
        @JvmStatic private external fun nativeStop(handle: Long)
        @JvmStatic private external fun nativeSetSourceGain(handle: Long, sourceId: Int, gainDb: Float)
        @JvmStatic private external fun nativePushInputPcm(handle: Long, samples: FloatArray, channels: Int, frames: Int)
        @JvmStatic private external fun nativePushPlayerPcm(handle: Long, samples: FloatArray, channels: Int, frames: Int)
        @JvmStatic private external fun nativeUpdatePipeline(
            handle: Long, targetDb: Float, maxGainDb: Float,
            agcAttack: Float, agcRelease: Float,
            outGainDb: Float, enabled: Boolean
        )
        @JvmStatic private external fun nativeInputPeakDb(handle: Long): Float
        @JvmStatic private external fun nativeOutputPeakDb(handle: Long): Float
        @JvmStatic private external fun nativeOutputRmsDb(handle: Long): Float
        @JvmStatic private external fun nativeAgcGainDb(handle: Long): Float
        @JvmStatic private external fun nativeBrickwallGrDb(handle: Long): Float
    }
}
