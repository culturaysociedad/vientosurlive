#pragma once

#include "../AudioProcessor.h"
#include "../dsp/Biquad.h"
#include "../dsp/EnvelopeFollower.h"
#include "../dsp/GainSmoothing.h"
#include <atomic>
#include <array>

namespace radiosuite::audio {

// (a) AGC de entrada.
// Objetivo: nivelar la señal entrante a un target RMS antes del EQ multibanda,
// con ataque lento y release lento para no alterar la dinámica musical.
class Agc : public AudioProcessor {
public:
    struct Params {
        float targetLevelDb   = -18.0f; // nivel objetivo RMS
        float maxGainDb        = 24.0f; // ganancia máxima aplicable
        float attackMs         = 150.0f;
        float releaseMs        = 800.0f;
        float sampleRate       = 48000.0f;
    };

    explicit Agc(const Params& p);

    bool process(AudioBlock& block) override;
    void reset() override;
    const char* name() const override { return "AGC"; }

    // Actualización thread-safe desde hilo de control.
    void setParameters(const Params& p);
    float currentGainDb() const { return currentGainDb_.load(); }

private:
    std::atomic<Params> params_;
    EnvelopeFollower rmsFollower_;
    GainSmoothing gainSmoother_;
    std::atomic<float> currentGainDb_{0.0f};
};

} // namespace radiosuite::audio
