#pragma once

#include "../AudioProcessor.h"
#include "../dsp/GainSmoothing.h"
#include <atomic>
#include <vector>

namespace radiosuite::audio {

// (e) Limitador de picos / Brickwall final a 0 dBFS.
// Limitador de lookahead (~5 ms) con attack instantáneo controlado por
// interpolación de ganancia hacia abajo, release suave, y ceiling configurable.
// Garantiza que ningún sample exceda el ceiling, actuando como "muro" final
// antes de enviar la señal al monitor y al codificador de streaming.

struct BrickwallParams {
    float ceilingDb   = -0.3f;   // techo de salida (ej. -0.3 dB para cabecera digital)
    float releaseMs   = 120.0f;  // release de la reducción de ganancia
    float lookaheadMs = 5.0f;    // buffer de anticipación
};

class BrickwallLimiter : public AudioProcessor {
public:
    explicit BrickwallLimiter(float sampleRate, std::size_t maxBlockSize = 1024);

    bool process(AudioBlock& block) override;
    void reset() override;
    const char* name() const override { return "Brickwall"; }

    void setParameters(const BrickwallParams& p);
    void setSampleRate(float sr);
    float gainReductionDb() const { return grDb_.load(); }

private:
    float sampleRate_;
    std::atomic<BrickwallParams> params_;
    GainSmoothing gainSmoother_;
    std::atomic<float> grDb_{0.0f};

    // Buffer circular de lookahead por canal.
    std::size_t lookaheadSamples_{0};
    std::size_t writePos_{0};
    std::vector<float> delayL_, delayR_;   // asignado una sola vez

    void resizeDelay();
};

} // namespace radiosuite::audio
