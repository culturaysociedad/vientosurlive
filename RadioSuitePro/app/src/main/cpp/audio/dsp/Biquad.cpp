#include "Biquad.h"
#include <cmath>

namespace radiosuite::audio {

namespace biquad_design {

namespace {
constexpr float kPi = 3.14159265358979323846f;

inline float safeGain(float gainDb) {
    return std::pow(10.0f, gainDb * 0.05f); // -> amplitud lineal
}

// RBJ Audio EQ Cookbook. w0 = 2*pi*f0/fs, alpha = sin(w0)/(2*Q).
inline BiquadCoeffs build(float w0, float alpha, float b0, float b1, float b2,
                          float a0, float a1, float a2) {
    return BiquadCoeffs{
        .a0 = b0 / a0, .a1 = b1 / a0, .a2 = b2 / a0,
        .b1 = a1 / a0, .b2 = a2 / a0
    };
}
} // namespace

BiquadCoeffs peaking(float sr, float f0, float gainDb, float q) {
    const float A  = safeGain(gainDb);
    const float w0 = 2.0f * kPi * f0 / sr;
    const float cw = std::cos(w0);
    const float sw = std::sin(w0);
    const float a  = sw / (2.0f * q);
    const float b0 = 1.0f + a * A;
    const float b1 = -2.0f * cw;
    const float b2 = 1.0f - a * A;
    const float a0 = 1.0f + a / A;
    const float a1 = -2.0f * cw;
    const float a2 = 1.0f - a / A;
    return build(w0, a, b0, b1, b2, a0, a1, a2);
}

BiquadCoeffs lowShelf(float sr, float f0, float gainDb, float q) {
    const float A  = safeGain(gainDb);
    const float w0 = 2.0f * kPi * f0 / sr;
    const float cw = std::cos(w0);
    const float sw = std::sin(w0);
    const float a  = sw / (2.0f * q);
    const float sq = 2.0f * std::sqrt(A) * a;
    const float b0 = A * ((A + 1) - (A - 1) * cw + sq);
    const float b1 = 2.0f * A * ((A - 1) - (A + 1) * cw);
    const float b2 = A * ((A + 1) - (A - 1) * cw - sq);
    const float a0 = (A + 1) + (A - 1) * cw + sq;
    const float a1 = -2.0f * ((A - 1) + (A + 1) * cw);
    const float a2 = (A + 1) + (A - 1) * cw - sq;
    return build(w0, a, b0, b1, b2, a0, a1, a2);
}

BiquadCoeffs highShelf(float sr, float f0, float gainDb, float q) {
    const float A  = safeGain(gainDb);
    const float w0 = 2.0f * kPi * f0 / sr;
    const float cw = std::cos(w0);
    const float sw = std::sin(w0);
    const float a  = sw / (2.0f * q);
    const float sq = 2.0f * std::sqrt(A) * a;
    const float b0 = A * ((A + 1) + (A - 1) * cw + sq);
    const float b1 = -2.0f * A * ((A - 1) + (A + 1) * cw);
    const float b2 = A * ((A + 1) + (A - 1) * cw - sq);
    const float a0 = (A + 1) - (A - 1) * cw + sq;
    const float a1 = 2.0f * ((A - 1) - (A + 1) * cw);
    const float a2 = (A + 1) - (A - 1) * cw - sq;
    return build(w0, a, b0, b1, b2, a0, a1, a2);
}

BiquadCoeffs lowPass(float sr, float f0, float q) {
    const float w0 = 2.0f * kPi * f0 / sr;
    const float cw = std::cos(w0);
    const float sw = std::sin(w0);
    const float a  = sw / (2.0f * q);
    const float b0 = (1.0f - cw) * 0.5f;
    const float b1 = 1.0f - cw;
    const float b2 = (1.0f - cw) * 0.5f;
    const float a0 = 1.0f + a;
    const float a1 = -2.0f * cw;
    const float a2 = 1.0f - a;
    return build(w0, a, b0, b1, b2, a0, a1, a2);
}

BiquadCoeffs highPass(float sr, float f0, float q) {
    const float w0 = 2.0f * kPi * f0 / sr;
    const float cw = std::cos(w0);
    const float sw = std::sin(w0);
    const float a  = sw / (2.0f * q);
    const float b0 = (1.0f + cw) * 0.5f;
    const float b1 = -(1.0f + cw);
    const float b2 = (1.0f + cw) * 0.5f;
    const float a0 = 1.0f + a;
    const float a1 = -2.0f * cw;
    const float a2 = 1.0f - a;
    return build(w0, a, b0, b1, b2, a0, a1, a2);
}

BiquadCoeffs notch(float sr, float f0, float q) {
    const float w0 = 2.0f * kPi * f0 / sr;
    const float cw = std::cos(w0);
    const float sw = std::sin(w0);
    const float a  = sw / (2.0f * q);
    const float b0 = 1.0f;
    const float b1 = -2.0f * cw;
    const float b2 = 1.0f;
    const float a0 = 1.0f + a;
    const float a1 = -2.0f * cw;
    const float a2 = 1.0f - a;
    return build(w0, a, b0, b1, b2, a0, a1, a2);
}

} // namespace biquad_design

} // namespace radiosuite::audio
