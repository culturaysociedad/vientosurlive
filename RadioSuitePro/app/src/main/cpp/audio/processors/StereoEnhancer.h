#pragma once

#include "../AudioProcessor.h"
#include "../dsp/Biquad.h"
#include <atomic>

namespace radiosuite::audio {

// (d) Expansor / Mejorador de presencia estéreo.
// Combina:
//   - Mid/Side processing para ajustar el ancho estéreo (width 0..2).
//   - Filtro de realce de alta frecuencia (air) tipo HighShelf.
//   - Filtro de paso bajo suave para evitar exceso de brillo post-compresión.
struct StereoEnhancerParams {
    float width     = 1.2f;   // 0 = mono, 1 = original, >1 = widened
    float airGainDb = 2.0f;   // realce de agudos
    float airFreqHz = 12000.0f;
    float lowpassHz = 19000.0f;
};

class StereoEnhancer : public AudioProcessor {
public:
    explicit StereoEnhancer(float sampleRate);

    bool process(AudioBlock& block) override;
    void reset() override;
    const char* name() const override { return "StereoEnhancer"; }

    void setParameters(const StereoEnhancerParams& p);
    void setSampleRate(float sr);

private:
    float sampleRate_;
    std::atomic<StereoEnhancerParams> params_;
    Biquad airShelf_[2];   // HighShelf por canal
    Biquad lowpass_[2];    // LPF por canal
    void rebuildFilters();
};

} // namespace radiosuite::audio
