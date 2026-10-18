#include "StereoEnhancer.h"
#include "../dsp/Biquad.h"
#include <cmath>

namespace radiosuite::audio {

StereoEnhancer::StereoEnhancer(float sampleRate)
    : sampleRate_(sampleRate) {
    rebuildFilters();
}

void StereoEnhancer::setSampleRate(float sr) {
    if (sr == sampleRate_ || sr <= 0) return;
    sampleRate_ = sr;
    rebuildFilters();
}

void StereoEnhancer::setParameters(const StereoEnhancerParams& p) {
    params_.store(p);
    rebuildFilters();
}

void StereoEnhancer::rebuildFilters() {
    const StereoEnhancerParams p = params_.load();
    const BiquadCoeffs air = biquad_design::highShelf(
        sampleRate_, p.airFreqHz, p.airGainDb, 0.707f);
    const BiquadCoeffs lp = biquad_design::lowPass(
        sampleRate_, p.lowpassHz, 0.707f);
    airShelf_[0].setCoeffs(air); airShelf_[1].setCoeffs(air);
    lowpass_[0].setCoeffs(lp);   lowpass_[1].setCoeffs(lp);
}

bool StereoEnhancer::process(AudioBlock& block) {
    const StereoEnhancerParams p = params_.load();
    const std::size_t n = block.numFrames;
    float* L = block.channelData[0];
    float* R = (block.numChannels > 1) ? block.channelData[1] : L;
    if (block.numChannels < 2) {
        // Mono: solo aplica air shelf + lowpass, sin widening.
        airShelf_[0].process(L, L, n);
        lowpass_[0].process(L, L, n);
        return true;
    }

    // 1) Mid/Side widening.
    //   M = (L+R)/2, S = (L-R)/2 ; L' = M + width*S, R' = M - width*S
    const float w = p.width;
    for (std::size_t i = 0; i < n; ++i) {
        const float m = 0.5f * (L[i] + R[i]);
        const float s = 0.5f * (L[i] - R[i]);
        L[i] = m + w * s;
        R[i] = m - w * s;
    }

    // 2) Air shelf (realce de agudos) por canal.
    airShelf_[0].process(L, L, n);
    airShelf_[1].process(R, R, n);

    // 3) Lowpass suave para frenar exceso de brillo post-compresión.
    lowpass_[0].process(L, L, n);
    lowpass_[1].process(R, R, n);
    return true;
}

void StereoEnhancer::reset() {
    airShelf_[0].reset(); airShelf_[1].reset();
    lowpass_[0].reset();  lowpass_[1].reset();
}

} // namespace radiosuite::audio
