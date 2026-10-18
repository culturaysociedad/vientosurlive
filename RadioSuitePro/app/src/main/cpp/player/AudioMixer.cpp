#include "AudioMixer.h"
#include <cmath>
#include <algorithm>

namespace radiosuite::audio {

namespace {
inline float dbToGain(float db) { return std::pow(10.0f, db * 0.05f); }
} // namespace

AudioMixer::AudioMixer(float sampleRate)
    : sampleRate_(sampleRate), crossfader_() {
    crossfader_.setSampleRate(sampleRate);
    scratchL_.assign(1024, 0.0f);
    scratchR_.assign(1024, 0.0f);
    for (auto& s : slots_) { s.gain.store(1.0f); s.active = false; s.source = nullptr; }
}

void AudioMixer::setSource(std::size_t id, AudioSource* source, float gainDb) {
    if (id >= kMaxSources) return;
    slots_[id].source = source;
    slots_[id].gain.store(dbToGain(gainDb));
    slots_[id].active = (source != nullptr);
}

void AudioMixer::setSourceGain(std::size_t id, float gainDb) {
    if (id >= kMaxSources) return;
    slots_[id].gain.store(dbToGain(gainDb), std::memory_order_relaxed);
}

void AudioMixer::setSampleRate(float sr) {
    sampleRate_ = sr;
    crossfader_.setSampleRate(sr);
}

void AudioMixer::startCrossfade(float durationMs) {
    crossfader_.start(durationMs);
}

void AudioMixer::mix(AudioBlock& block) {
    const std::size_t n = block.numFrames;
    if (n > scratchL_.size()) {
        scratchL_.resize(n, 0.0f);
        scratchR_.resize(n, 0.0f);
    }

    // Coeficientes de crossfade (A=playlist actual, B=entrante).
    float gainA = 1.0f, gainB = 0.0f;
    crossfader_.step(n, gainA, gainB);

    // Limpia el bloque de salida.
    float* outL = block.channelData[0];
    float* outR = (block.numChannels > 1) ? block.channelData[1] : outL;
    std::fill_n(outL, n, 0.0f);
    if (block.numChannels > 1) std::fill_n(outR, n, 0.0f);

    for (std::size_t s = 0; s < kMaxSources; ++s) {
        if (!slots_[s].active || !slots_[s].source) continue;
        // Renderiza la fuente en scratch.
        if (!slots_[s].source->render(scratchL_.data(), scratchR_.data(), n)) {
            slots_[s].active = false;
            continue;
        }
        const float g = slots_[s].gain.load(std::memory_order_relaxed);
        // Aplica ganancia de fuente + coeficiente de crossfade (id 0 = A, id 1 = B).
        const float cf = (s == 0) ? gainA : (s == 1) ? gainB : 1.0f;
        const float total = g * cf;
        for (std::size_t i = 0; i < n; ++i) {
            outL[i] += scratchL_[i] * total;
            if (block.numChannels > 1) outR[i] += scratchR_[i] * total;
        }
    }
}

} // namespace radiosuite::audio
