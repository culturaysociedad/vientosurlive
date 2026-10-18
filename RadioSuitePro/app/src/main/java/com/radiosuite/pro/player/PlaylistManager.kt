package com.radiosuite.pro.player

import com.radiosuite.pro.model.Track

/**
 * Gestiona la cola de reproducción, los crossfades automáticos entre pistas
 * y la mezcla entre la entrada de micrófono/consola y el reproductor de música.
 *
 * La decodificación real de MP3/OGG/Opus/WAV se realiza con ExoPlayer, que
 * entrega PCM float al mezclador nativo (AudioMixer en C++). Esta clase
 * coordina la cola a nivel lógico y dispara las transiciones.
 */
class PlaylistManager {

    private val queue = ArrayDeque<Track>()
    @Volatile private var currentIndex = -1
    @Volatile var crossfadeDurationMs: Float = 3000f
        private set

    /** Añade una pista al final de la cola. */
    fun enqueue(track: Track) { queue.addLast(track) }

    /** Reemplaza la cola completa. */
    fun setPlaylist(tracks: List<Track>) {
        queue.clear()
        queue.addAll(tracks)
        currentIndex = if (tracks.isEmpty()) -1 else 0
    }

    fun current(): Track? = queue.getOrNull(currentIndex)

    fun next(): Track? {
        if (currentIndex + 1 < queue.size) {
            currentIndex++
            return queue[currentIndex]
        }
        return null
    }

    /** Inicia el crossfade a la siguiente pista. Devuelve la pista entrante. */
    fun crossfadeToNext(): Track? = next()?.also {
        // El disparo del crossfader real se hace en AudioMixer.startCrossfade()
        // vía el puente nativo cuando se confirma el PCM disponible.
    }

    fun setCrossfadeDuration(ms: Float) { crossfadeDurationMs = ms }

    fun size(): Int = queue.size
    fun isEmpty(): Boolean = queue.isEmpty()
}
