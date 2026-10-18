#pragma once

#include <cmath>

namespace radiosuite::audio {

// Crossfader de igual-potencia (constante de 3 dB) para transiciones suaves
// entre pistas de playlist. Transición lineal en dominio de amplitud
// ponderada por cos/sen para mantener la potencia total.
class Crossfader {
public:
    void setSampleRate(float sr) { sr_ = sr; }
    void reset() { pos_ = 0.0f; active_ = false; }

    void start(float durationMs) {
        durationMs_ = durationMs;
        pos_ = 0.0f;
        active_ = true;
    }

    // Avanza `numFrames` y escribe los coeficientes de ganancia para A y B.
    inline void step(std::size_t numFrames, float& gainA, float& gainB) {
        if (!active_) { gainA = 1.0f; gainB = 0.0f; return; }
        const float inc = (durationMs_ > 0)
            ? (1.0f / (durationMs_ * 0.001f * sr_)) : 1.0f;
        for (std::size_t i = 0; i < numFrames; ++i) {
            pos_ += inc;
            if (pos_ >= 1.0f) { pos_ = 1.0f; active_ = false; break; }
        }
        // Equal-power: ganA = cos(p * pi/2), ganB = sin(p * pi/2)
        const float angle = pos_ * 1.5707963267948966f;
        gainA = std::cos(angle);
        gainB = std::sin(angle);
    }

    bool active() const { return active_; }

private:
    float sr_ = 48000.0f;
    float durationMs_ = 3000.0f;
    float pos_ = 0.0f;
    bool  active_ = false;
};

} // namespace radiosuite::audio
