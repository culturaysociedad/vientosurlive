package com.radiosuite.pro.dsp

/**
 * Instantánea inmutable de parámetros del pipeline DSP.
 * Se copia al motor C++ vía [DspEngine.updatePipeline], que la intercambia
 * atómicamente en el hilo de audio sin bloquear el callback de Oboe.
 */
data class DspParameters(
    val enabled: Boolean = true,
    val agcTargetDb: Float = -18f,
    val agcMaxGainDb: Float = 24f,
    val agcAttackMs: Float = 150f,
    val agcReleaseMs: Float = 800f,
    val outputGainDb: Float = 0f,
    // Las bandas de EQ y compresión se envían en una segunda pasada nativa
    // (extensible) para mantener el bridge JNI inicial compacto.
)
