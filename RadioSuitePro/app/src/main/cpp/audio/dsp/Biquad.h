#pragma once

#include <array>

namespace radiosuite::audio {

// Filtro biquad IIR en forma Direct Form II Transposed.
// Coeficientes calculados externamente (funciones de diseño en .cpp).
struct BiquadCoeffs {
    float a0 = 1.0f, a1 = 0.0f, a2 = 0.0f;
    float b1 = 0.0f, b2 = 0.0f;
};

class Biquad {
public:
    void setCoeffs(const BiquadCoeffs& c) { c_ = c; }
    void reset() { z1_ = z2_ = 0.0f; }

    // Procesa un buffer mono in-place.
    inline void process(const float* in, float* out, std::size_t n) {
        const float a1 = c_.a1, a2 = c_.a2, b1 = c_.b1, b2 = c_.b2;
        float z1 = z1_, z2 = z2_;
        for (std::size_t i = 0; i < n; ++i) {
            const float x = in[i];
            const float y = c_.a0 * x + z1;
            z1 = a1 * x + z2 - b1 * y;
            z2 = a2 * x - b2 * y;
            out[i] = y;
        }
        z1_ = z1; z2_ = z2;
    }

private:
    BiquadCoeffs c_;
    float z1_ = 0.0f, z2_ = 0.0f;
};

// Funciones de diseño de coeficientes (RBJ Audio EQ Cookbook).
namespace biquad_design {
BiquadCoeffs peaking(float sr, float f0, float gainDb, float q);
BiquadCoeffs lowShelf(float sr, float f0, float gainDb, float q);
BiquadCoeffs highShelf(float sr, float f0, float gainDb, float q);
BiquadCoeffs lowPass(float sr, float f0, float q);
BiquadCoeffs highPass(float sr, float f0, float q);
BiquadCoeffs notch(float sr, float f0, float q);
}

} // namespace radiosuite::audio
