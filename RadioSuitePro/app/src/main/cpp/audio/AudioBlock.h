#pragma once

// Bloque de audio estéreo de punto flotante, unidad de intercambio del pipeline.
// Todos los procesadores operan sobre AudioBlock in-place para evitar alocaciones
// en el hilo de audio de tiempo real.

#include <cstdint>
#include <cstddef>

namespace radiosuite::audio {

constexpr std::size_t kMaxChannels = 2;

struct AudioBlock {
    const std::size_t numChannels;   // normalmente 2 (estéreo)
    const std::size_t numFrames;     // frames por bloque (128..1024 típico)
    float* channelData[kMaxChannels]; // channelData[c] = numFrames muestras float [-1,1]

    AudioBlock(std::size_t channels, std::size_t frames, float* left, float* right)
        : numChannels(channels), numFrames(frames) {
        channelData[0] = left;
        channelData[1] = (channels > 1) ? right : left; // mono -> estéreo duplicado
    }

    std::size_t totalSamples() const { return numChannels * numFrames; }
};

} // namespace radiosuite::audio
