#pragma once

#include <cmath>

namespace radiosuite::audio {

// Seguidor de envolvente (RMS o pico) con coeficientes de attack/release
// derivados de constantes de tiempo. Sin alocaciones, seguro en hilo de audio.
class EnvelopeFollower {
public:
    void setSampleRate(float sr) { sr_ = sr; recalc(); }

    void setTimesMs(float attackMs, float releaseMs) {
        attackMs_ = attackMs; releaseMs_ = releaseMs; recalc();
    }

    void reset() { env_ = 0.0f; }

    // Modo RMS con ventana instantánea (mean square suavizada).
    inline float processRms(float sample) {
        const float sq = sample * sample;
        const float a = (sq > env_) ? attackCoef_ : releaseCoef_;
        env_ = a * env_ + (1.0f - a) * sq;
        return std::sqrt(env_ + 1e-12f);
    }

    // Modo pico absoluto.
    inline float processPeak(float sample) {
        const float s = std::fabs(sample);
        const float a = (s > env_) ? attackCoef_ : releaseCoef_;
        env_ = a * env_ + (1.0f - a) * s;
        return env_;
    }

    float current() const { return env_; }

private:
    float sr_ = 48000.0f;
    float attackMs_ = 10.0f, releaseMs_ = 100.0f;
    float attackCoef_ = 0.0f, releaseCoef_ = 0.0f;
    float env_ = 0.0f;

    void recalc() {
        // coef = exp(-1 / (t * sr)); a mayor tiempo, más lento.
        attackCoef_  = std::exp(-1.0f / (attackMs_  * 0.001f * sr_));
        releaseCoef_ = std::exp(-1.0f / (releaseMs_ * 0.001f * sr_));
    }
};

} // namespace radiosuite::audio
