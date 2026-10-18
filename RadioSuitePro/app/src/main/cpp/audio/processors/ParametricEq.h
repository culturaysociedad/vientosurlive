#pragma once

#include "../AudioProcessor.h"
#include "../dsp/Biquad.h"
#include <atomic>
#include <array>
#include <vector>

namespace radiosuite::audio {

// (b) Ecualizador paramétrico multibanda.
// N bandas configurables, cada una: tipo (LowShelf / Peaking / HighShelf / Notch),
// frecuencia, Q y ganancia. Implementado como cascada de filtros biquad IIR.

enum class BandType { LowShelf, Peaking, HighShelf, Notch, LowPass, HighPass };

struct EqBandParams {
    BandType type = BandType::Peaking;
    float freqHz   = 1000.0f;
    float gainDb   = 0.0f;
    float q        = 0.707f;
};

class ParametricEq : public AudioProcessor {
public:
    static constexpr std::size_t kMaxBands = 8;

    explicit ParametricEq(float sampleRate, std::size_t numBands = 5);

    bool process(AudioBlock& block) override;
    void reset() override;
    const char* name() const override { return "ParametricEq"; }

    void setBand(std::size_t index, const EqBandParams& p);
    void setSampleRate(float sr);

private:
    float sampleRate_;
    std::size_t numBands_;
    std::atomic<std::size_t> activeBands_;
    std::array<EqBandParams, kMaxBands> bandParams_;
    // Filtros por banda por canal (coeficientes estado Direct Form II Transposed)
    std::array<std::array<Biquad, 2>, kMaxBands> biquads_;

    void rebuildBand(std::size_t index);
};

} // namespace radiosuite::audio
