#include "BrickwallLimiter.h"
#include <cmath>
#include <algorithm>

namespace radiosuite::audio {

namespace {
inline float dbToGain(float db) { return std::pow(10.0f, db * 0.05f); }
inline float gainToDb(float g)  { return 20.0f * std::log10(g + 1e-12f); }
} // namespace

BrickwallLimiter::BrickwallLimiter(float sampleRate, std::size_t maxBlockSize)
    : sampleRate_(sampleRate),
      gainSmoother_(),
      lookaheadSamples_(0),
      writePos_(0) {
    gainSmoother_.setSampleRate(sampleRate);
    gainSmoother_.setTimeMs(1.0f); // ataque muy rápido para el muro
    gainSmoother_.setImmediate(1.0f);
    // Pre-reserva para el máximo bloque esperado + lookahead.
    delayL_.resize(maxBlockSize + 2048, 0.0f);
    delayR_.resize(maxBlockSize + 2048, 0.0f);
    resizeDelay();
}

void BrickwallLimiter::setSampleRate(float sr) {
    if (sr == sampleRate_ || sr <= 0) return;
    sampleRate_ = sr;
    gainSmoother_.setSampleRate(sr);
    resizeDelay();
}

void BrickwallLimiter::setParameters(const BrickwallParams& p) {
    params_.store(p);
    gainSmoother_.setTimeMs(std::max(0.1f, p.releaseMs * 0.02f)); // release influye en el suavizado
    resizeDelay();
}

void BrickwallLimiter::resizeDelay() {
    const BrickwallParams p = params_.load();
    const std::size_t wanted = static_cast<std::size_t>(p.lookaheadMs * 0.001f * sampleRate_);
    if (wanted + 8 < delayL_.size()) {
        lookaheadSamples_ = wanted;
        writePos_ = 0;
        std::fill(delayL_.begin(), delayL_.end(), 0.0f);
        std::fill(delayR_.begin(), delayR_.end(), 0.0f);
    }
}

bool BrickwallLimiter::process(AudioBlock& block) {
    const BrickwallParams p = params_.load();
    const float ceiling = dbToGain(p.ceilingDb);
    const std::size_t n = block.numFrames;
    float* L = block.channelData[0];
    float* R = (block.numChannels > 1) ? block.channelData[1] : L;

    // Para cada frame:
    //   1) Escribimos la muestra en el delay (lookahead).
    //   2) Inspeccionamos la muestra que sale del delay.
    //   3) Si excede el ceiling, reducimos la ganancia suavemente.
    //   4) Salida = muestra retrasada * ganancia suavizada, clampeada al ceiling.
    float minGain = 1.0f;
    for (std::size_t i = 0; i < n; ++i) {
        // Pico del frame entrante (máx de |L|, |R|).
        const float peakIn = std::max(std::fabs(L[i]),
                                       (block.numChannels > 1) ? std::fabs(R[i]) : 0.0f);

        //写入延迟线。
        delayL_[writePos_] = L[i];
        if (block.numChannels > 1) delayR_[writePos_] = R[i];

        // Posición de lectura = writePos - lookahead (wrap).
        const std::size_t readPos = (writePos_ + delayL_.size() - lookaheadSamples_) % delayL_.size();
        const float delayedL = delayL_[readPos];
        const float delayedR = (block.numChannels > 1) ? delayR_[readPos] : delayedL;

        // Determina ganancia objetivo para mantener debajo del ceiling.
        const float peakOut = std::max(std::fabs(delayedL), std::fabs(delayedR));
        float targetGain = 1.0f;
        if (peakOut > ceiling) {
            targetGain = ceiling / (peakOut + 1e-12f);
        }
        // Ataque instantáneo (gainSmoother baja rápido), release lento.
        const float g = (targetGain < gainSmoother_.current())
            ? targetGain
            : gainSmoother_.processTo(targetGain, 1);
        if (g < minGain) minGain = g;

        // Salida clampeada (seguro frente a redondeos).
        L[i] = std::clamp(delayedL * g, -ceiling, ceiling);
        if (block.numChannels > 1) R[i] = std::clamp(delayedR * g, -ceiling, ceiling);

        writePos_ = (writePos_ + 1) % delayL_.size();
    }
    grDb_.store(gainToDb(minGain) - 0.0f, std::memory_order_relaxed);
    // Publicamos la reducción como valor negativo (0 = sin reducción).
    grDb_.store(gainToDb(minGain), std::memory_order_relaxed);
    return true;
}

void BrickwallLimiter::reset() {
    std::fill(delayL_.begin(), delayL_.end(), 0.0f);
    std::fill(delayR_.begin(), delayR_.end(), 0.0f);
    writePos_ = 0;
    gainSmoother_.reset();
    gainSmoother_.setImmediate(1.0f);
    grDb_.store(0.0f);
}

} // namespace radiosuite::audio
