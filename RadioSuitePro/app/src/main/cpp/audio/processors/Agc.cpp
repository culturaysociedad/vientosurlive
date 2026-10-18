#include "Agc.h"
#include <cmath>

namespace radiosuite::audio {

namespace {
inline float dbToGain(float db) { return std::pow(10.0f, db * 0.05f); }
inline float gainToDb(float g)  { return 20.0f * std::log10(g + 1e-12f); }
inline float peakToDb(float p)  { return 20.0f * std::log10(p + 1e-12f); }
} // namespace

Agc::Agc(const Params& p)
    : params_(p),
      rmsFollower_(),
      gainSmoother_() {
    rmsFollower_.setSampleRate(p.sampleRate);
    rmsFollower_.setTimesMs(p.attackMs, p.releaseMs);
    gainSmoother_.setSampleRate(p.sampleRate);
    gainSmoother_.setTimeMs(50.0f); // ramp suave para zipper-free
    gainSmoother_.setImmediate(1.0f);
}

void Agc::setParameters(const Params& p) {
    params_.store(p);
    rmsFollower_.setSampleRate(p.sampleRate);
    rmsFollower_.setTimesMs(p.attackMs, p.releaseMs);
}

bool Agc::process(AudioBlock& block) {
    const Params p = params_.load();
    const float targetLin = dbToGain(p.targetLevelDb);
    const float maxGainLin = dbToGain(p.maxGainDb);

    // Medición RMS sobre el bloque (promedio de canales).
    float sumSq = 0.0f;
    const std::size_t total = block.numFrames * block.numChannels;
    for (std::size_t c = 0; c < block.numChannels; ++c) {
        const float* d = block.channelData[c];
        for (std::size_t i = 0; i < block.numFrames; ++i) sumSq += d[i] * d[i];
    }
    const float rms = std::sqrt(sumSq / static_cast<float>(total) + 1e-12f);
    const float rmsSmoothed = rmsFollower_.processRms(rms);

    // Ganancia deseada = target / rms, clampeada a [0, maxGain].
    float desiredGain = (rmsSmoothed > 1e-6f) ? (targetLin / rmsSmoothed) : 1.0f;
    desiredGain = std::clamp(desiredGain, 0.0f, maxGainLin);

    // Suavizado hacia la ganancia deseada (anti-zipper).
    const float gain = gainSmoother_.processTo(desiredGain, block.numFrames);
    currentGainDb_.store(gainToDb(gain), std::memory_order_relaxed);

    // Aplica ganancia a todos los canales.
    for (std::size_t c = 0; c < block.numChannels; ++c) {
        float* d = block.channelData[c];
        for (std::size_t i = 0; i < block.numFrames; ++i) d[i] *= gain;
    }
    return true;
}

void Agc::reset() {
    rmsFollower_.reset();
    gainSmoother_.reset();
    gainSmoother_.setImmediate(1.0f);
    currentGainDb_.store(0.0f);
}

} // namespace radiosuite::audio
