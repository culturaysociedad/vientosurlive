#pragma once

#include "../audio/AudioBlock.h"
#include "../audio/dsp/Crossfader.h"
#include <vector>
#include <array>
#include <atomic>

namespace radiosuite::audio {

// Fuente lógica de audio que el mezclador combina. El reproductor, el
// micrófono y cualquier entrada externa implementan esta interfaz.
class AudioSource {
public:
    virtual ~AudioSource() = default;
    // Rellena `out` con `numFrames` muestras estéreo float. Devuelve false
    // cuando la fuente se ha agotado (p.ej. pista finalizada).
    virtual bool render(float* left, float* right, std::size_t numFrames) = 0;
    virtual bool active() const = 0;
};

// Mezclador N->estéreo con ganancia por fuente y crossfader automático entre
// el reproductor A y B (autoplay/crossfade de playlist).
class AudioMixer {
public:
    static constexpr std::size_t kMaxSources = 4;

    AudioMixer(float sampleRate);

    // Registra una fuente con un id estable y ganancia inicial.
    void setSource(std::size_t id, AudioSource* source, float gainDb);
    void setSourceGain(std::size_t id, float gainDb);

    // Mezcla todas las fuentes activas en `block`. Bloque in-place.
    void mix(AudioBlock& block);

    // Crossfade automático de playlist: inicia transición A->B en `durationMs`.
    void startCrossfade(float durationMs);
    void setSampleRate(float sr);

private:
    float sampleRate_;
    struct Slot { AudioSource* source = nullptr; std::atomic<float> gain; bool active = false; };
    std::array<Slot, kMaxSources> slots_;
    Crossfader crossfader_;
    std::vector<float> scratchL_, scratchR_;
};

} // namespace radiosuite::audio
