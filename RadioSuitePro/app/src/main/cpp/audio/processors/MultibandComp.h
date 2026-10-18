#pragma once

#include "../AudioProcessor.h"
#include "../dsp/Biquad.h"
#include "../dsp/EnvelopeFollower.h"
#include "../dsp/GainSmoothing.h"
#include <atomic>
#include <array>

namespace radiosuite::audio {

// (c) Compresor / Limitador multibanda.
// Divide la señal estéreo en N bandas vía crossover Linkwitz-Riley (4º orden =
// 2x biquad por banda), comprime cada banda de forma independiente con
// ataque/release/razón/rango propios, y recombina sumando las salidas.
// Objetivo: densidad broadcast FM sin distorsión audible.

struct CompBandParams {
    float crossoverHz   = 120.0f;
    float thresholdDb   = -24.0f;
    float ratio         = 3.0f;      // 1..20
    float attackMs      = 5.0f;
    float releaseMs     = 120.0f;
    float makeupDb      = 0.0f;
    float kneeDb        = 6.0f;      // soft knee
};

class MultibandComp : public AudioProcessor {
public:
    static constexpr std::size_t kMaxBands = 5;

    explicit MultibandComp(float sampleRate, std::size_t numBands = 4);

    bool process(AudioBlock& block) override;
    void reset() override;
    const char* name() const override { return "MultibandComp"; }

    void setBand(std::size_t index, const CompBandParams& p);
    void setSampleRate(float sr);

    // Reducción de ganancia por banda para métricas de la UI.
    float gainReductionDb(std::size_t band) const {
        return grDb_[band].load();
    }

private:
    float sampleRate_;
    std::size_t numBands_;
    std::array<CompBandParams, kMaxBands> params_;
    // Crossover Linkwitz-Riley: 2 biquad por banda por canal.
    std::array<std::array<Biquad, 2>, kMaxBands> lp_;
    std::array<std::array<Biquad, 2>, kMaxBands> hp_;
    std::array<EnvelopeFollower, kMaxBands> env_;
    std::array<GainSmoothing, kMaxBands> gainSmoother_;
    std::array<std::atomic<float>, kMaxBands> grDb_;

    void rebuildCrossover();
};

} // namespace radiosuite::audio
