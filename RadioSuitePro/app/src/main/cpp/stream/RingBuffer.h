#pragma once

#include <atomic>
#include <vector>
#include <memory>

namespace radiosuite::audio {

// RingBuffer lock-free de muestras float para desacoplar el hilo de audio
// (productor) del hilo de red (consumidor) del stream. SPMC seguro entre
// un productor y un consumidor usando índices atómicas con padding para
// evitar false sharing.
class RingBuffer {
public:
    explicit RingBuffer(std::size_t capacitySamples);

    // Devuelve el número de muestras disponibles para leer.
    std::size_t available() const;

    // Escribe muestras (hilo de audio). Devuelve cuántas escribió.
    std::size_t write(const float* src, std::size_t n);

    // Lee muestras (hilo de red). Devuelve cuántas leyó.
    std::size_t read(float* dst, std::size_t n);

    void clear();

private:
    std::vector<float> buffer_;
    const std::size_t mask_;           // capacity-1 (potencia de 2)
    alignas(64) std::atomic<std::size_t> writePos_{0};
    alignas(64) std::atomic<std::size_t> readPos_{0};
};

} // namespace radiosuite::audio
