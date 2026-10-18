package com.radiosuite.pro.model

/**
 * Perfil de emisión: configura el servidor de destino y el codec de streaming.
 */
data class BroadcastProfile(
    val name: String,
    val host: String,
    val port: Int = 8000,
    val mount: String = "/live",
    val password: String,
    val codec: CodecType = CodecType.Opus,
    val bitrate: Int = 128,
    val sampleRate: Int = 48000,
    val channels: Int = 2,
)

enum class CodecType { Opus, Vorbis, Mp3 }
