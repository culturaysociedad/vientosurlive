#include "ParametricEq.h"
#include "../dsp/Biquad.h"
#include <algorithm>

namespace radiosuite::audio {

ParametricEq::ParametricEq(float sampleRate, std::size_t numBands)
    : sampleRate_(sampleRate), numBands_(std::min(numBands, kMaxBands)) {
    activeBands_.store(numBands_);
    bandParams_.fill(EqBandParams{});
    for (std::size_t b = 0; b < numBands_; ++b) {
        biquads_[b][0].reset();
        biquads_[b][1].reset();
        rebuildBand(b);
    }
}

void ParametricEq::setSampleRate(float sr) {
    if (sr == sampleRate_ || sr <= 0) return;
    sampleRate_ = sr;
    for (std::size_t b = 0; b < numBands_; ++b) {
        biquads_[b][0].reset();
        biquads_[b][1].reset();
        rebuildBand(b);
    }
}

void ParametricEq::setBand(std::size_t index, const EqBandParams& p) {
    if (index >= numBands_) return;
    bandParams_[index] = p;
    rebuildBand(index);
}

void ParametricEq::rebuildBand(std::size_t index) {
    const EqBandParams& p = bandParams_[index];
    BiquadCoeffs c{};
    switch (p.type) {
        case BandType::Peaking:   c = biquad_design::peaking(sampleRate_, p.freqHz, p.gainDb, p.q); break;
        case BandType::LowShelf:  c = biquad_design::lowShelf(sampleRate_, p.freqHz, p.gainDb, p.q); break;
        case BandType::HighShelf: c = biquad_design::highShelf(sampleRate_, p.freqHz, p.gainDb, p.q); break;
        case BandType::Notch:     c = biquad_design::notch(sampleRate_, p.freqHz, p.q); break;
        case BandType::LowPass:   c = biquad_design::lowPass(sampleRate_, p.freqHz, p.q); break;
        case BandType::HighPass:  c = biquad_design::highPass(sampleRate_, p.freqHz, p.q); break;
    }
    biquads_[index][0].setCoeffs(c);
    biquads_[index][1].setCoeffs(c);
}

bool ParametricEq::process(AudioBlock& block) {
    const std::size_t active = activeBands_.load();
    // Cascada: cada banda procesa la salida de la anterior.
    for (std::size_t b = 0; b < active && b < numBands_; ++b) {
        for (std::size_t c = 0; c < block.numChannels && c < 2; ++c) {
            float* d = block.channelData[c];
            // Procesa in-place: Biquad soporta src == dst.
            biquads_[b][c].process(d, d, block.numFrames);
        }
    }
    return true;
}

void ParametricEq::reset() {
    for (std::size_t b = 0; b < numBands_; ++b) {
        biquads_[b][0].reset();
        biquads_[b][1].reset();
    }
}

} // namespace radiosuite::audio
