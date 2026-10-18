package com.radiosuite.pro.model

/**
 * Pista de audio de la playlist.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val uri: String,
    val durationMs: Long,
    val format: AudioFormat,
)

enum class AudioFormat { Mp3, Ogg, Opus, Wav, Aac, Flac }
