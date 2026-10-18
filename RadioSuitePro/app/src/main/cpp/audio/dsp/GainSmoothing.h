#pragma once

#include <cmath>

namespace radiosuite::audio {

// Suavizado de ganancia con rampa lineal por bloque. Evita "zipper noise"
// cuando la UI cambia parámetros. Sin alocaciones.
class GainSmoothing {
public:
    void setSampleRate(float sr) { sr_ = sr; recalc(); }
    void setTimeMs(float ms) { timeMs_ = ms; recalc(); }

    void reset() { current_ = 0.0f; }
    void setImmediate(float gain) { current_ = gain; }

    inline float processTo(float target, std::size_t n) {
        const float step = (target - current_) * stepPerSample_;
        for (std::size_t i = 0; i < n; ++i) current_ += step;
        return current_;
    }

    inline float current() const { return current_; }

private:
    float sr_ = 48000.0f;
    float timeMs_ = 20.0f;
    float stepPerSample_ = 0.0f;
    float current_ = 1.0f;

    void recalc() {
        const float samples = timeMs_ * 0.001f * sr_;
        stepPerSample_ = (samples > 1.0f) ? (1.0f / samples) : 1.0f;
    }
};

} // namespace radiosuite::audio
