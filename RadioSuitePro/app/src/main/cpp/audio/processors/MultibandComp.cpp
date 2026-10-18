#include "MultibandComp.h"
#include "../dsp/Biquad.h"
#include <cmath>
#include <algorithm>

namespace radiosuite::audio {

namespace {
inline float dbToGain(float db) { return std::pow(10.0f, db * 0.05f); }
inline float gainToDb(float g)  { return 20.0f * std::log10(g + 1e-12f); }
constexpr float kLinkwitzQ = 0.5f; // Q para LR4 ( Butterworth cascada 2x)
} // namespace

MultibandComp::MultibandComp(float sampleRate, std::size_t numBands)
    : sampleRate_(sampleRate), numBands_(std::min(numBands, kMaxBands)) {
    params_.fill(CompBandParams{});
    for (std::size_t b = 0; b < numBands_; ++b) {
        env_[b].setSampleRate(sampleRate);
        env_[b].setTimesMs(5.0f, 120.0f);
        gainSmoother_[b].setSampleRate(sampleRate);
        gainSmoother_[b].setTimeMs(10.0f);
        gainSmoother_[b].setImmediate(1.0f);
        grDb_[b].store(0.0f);
    }
    rebuildCrossover();
}

void MultibandComp::setSampleRate(float sr) {
    if (sr == sampleRate_ || sr <= 0) return;
    sampleRate_ = sr;
    for (std::size_t b = 0; b < numBands_; ++b) {
        env_[b].setSampleRate(sr);
        gainSmoother_[b].setSampleRate(sr);
    }
    rebuildCrossover();
}

void MultibandComp::setBand(std::size_t index, const CompBandParams& p) {
    if (index >= numBands_) return;
    params_[index] = p;
    env_[index].setTimesMs(p.attackMs, p.releaseMs);
    rebuildCrossover(); // crossoverHz pudo cambiar
}

void MultibandComp::rebuildCrossover() {
    // Cada banda (excepto la última) define un crossover que separa su región
    // de la siguiente. LP retiene la banda baja, HP pasa a la siguiente.
    for (std::size_t b = 0; b + 1 < numBands_; ++b) {
        const float f0 = params_[b].crossoverHz;
        // Linkwitz-Riley 4º orden = 2x Butterworth (Q=0.707) en cascada.
        const BiquadCoeffs lp = biquad_design::lowPass(sampleRate_, f0, 0.707f);
        const BiquadCoeffs hp = biquad_design::highPass(sampleRate_, f0, 0.707f);
        for (int c = 0; c < 2; ++c) {
            lp_[b][c].setCoeffs(lp);
            hp_[b][c].setCoeffs(hp);
            lp_[b][c].reset();
            hp_[b][c].reset();
        }
    }
}

bool MultibandComp::process(AudioBlock& block) {
    const std::size_t n = block.numFrames;
    const std::size_t ch = block.numChannels;
    float* const L = block.channelData[0];
    float* const R = (ch > 1) ? block.channelData[1] : L;

    // Buffers temporales por banda en stack (tamaño razonable para bloques).
    alignas(16) float bandL[2][1024];
    alignas(16) float bandR[2][1024];
    alignas(16) float tmpL[1024];
    alignas(16) float tmpR[1024];
    if (n > 1024) return true; // salvaguarda: bloques grandes se saltan

    // Mezcla final acumulada.
    alignas(16) float outL[1024] = {0};
    alignas(16) float outR[1024] = {0};

    // Trabajo progresivo: entrada vigente = (L,R); cada banda extrae su porción
    // con LP y deja el resto pasar con HP para la siguiente.
    const float* curL = L;
    const float* curR = R;
    int curIdx = 0; // índice de buffer "actual" (ping-pong)

    for (std::size_t b = 0; b < numBands_; ++b) {
        if (b + 1 < numBands_) {
            // LP -> banda actual, HP -> resto.
            float* bandLOut = bandL[curIdx];
            float* bandROut = bandR[curIdx];
            lp_[b][0].process(curL, bandLOut, n);
            lp_[b][1].process(curR, bandROut, n);
            // HP sobre la entrada para el resto (escribe en tmp, luego swap).
            hp_[b][0].process(curL, tmpL, n);
            hp_[b][1].process(curR, tmpR, n);
            curL = tmpL; curR = tmpR; // para la próxima iteración
            // Procesa la banda extraída.
            const CompBandParams& p = params_[b];
            float* sigL = bandLOut;
            float* sigR = bandROut;
            // Compresión por banda.
            float peak = 0.0f;
            for (std::size_t i = 0; i < n; ++i) {
                const float m = std::max(std::fabs(sigL[i]), std::fabs(sigR[i]));
                if (m > peak) peak = m;
            }
            const float envDb = gainToDb(env_[b].processPeak(peak));
            // Ganancia de compresión (soft knee).
            float grDb = 0.0f;
            const float thr = p.thresholdDb;
            const float knee = p.kneeDb;
            if (envDb > thr - knee * 0.5f) {
                const float over = envDb - thr;
                if (over > knee * 0.5f) {
                    grDb = -(over * (1.0f - 1.0f / p.ratio));
                } else {
                    // Zona de rodilla suave.
                    const float t = (over + knee * 0.5f) / knee;
                    grDb = -(t * t * knee * 0.5f * (1.0f - 1.0f / p.ratio));
                }
            }
            const float targetGain = dbToGain(grDb + p.makeupDb);
            const float g = gainSmoother_[b].processTo(targetGain, n);
            grDb_[b].store(grDb, std::memory_order_relaxed);
            for (std::size_t i = 0; i < n; ++i) {
                outL[i] += sigL[i] * g;
                outR[i] += sigR[i] * g;
            }
        } else {
            // Última banda: procesa todo lo que quede.
            const CompBandParams& p = params_[b];
            float peak = 0.0f;
            for (std::size_t i = 0; i < n; ++i) {
                const float m = std::max(std::fabs(curL[i]), std::fabs(curR[i]));
                if (m > peak) peak = m;
            }
            const float envDb = gainToDb(env_[b].processPeak(peak));
            float grDb = 0.0f;
            const float thr = p.thresholdDb;
            const float knee = p.kneeDb;
            if (envDb > thr - knee * 0.5f) {
                const float over = envDb - thr;
                if (over > knee * 0.5f) {
                    grDb = -(over * (1.0f - 1.0f / p.ratio));
                } else {
                    const float t = (over + knee * 0.5f) / knee;
                    grDb = -(t * t * knee * 0.5f * (1.0f - 1.0f / p.ratio));
                }
            }
            const float targetGain = dbToGain(grDb + p.makeupDb);
            const float g = gainSmoother_[b].processTo(targetGain, n);
            grDb_[b].store(grDb, std::memory_order_relaxed);
            for (std::size_t i = 0; i < n; ++i) {
                outL[i] += curL[i] * g;
                outR[i] += curR[i] * g;
            }
        }
    }

    // Copia de vuelta al bloque.
    std::copy_n(outL, n, L);
    if (ch > 1) std::copy_n(outR, n, R);
    return true;
}

void MultibandComp::reset() {
    for (std::size_t b = 0; b < numBands_; ++b) {
        env_[b].reset();
        gainSmoother_[b].reset();
        gainSmoother_[b].setImmediate(1.0f);
        grDb_[b].store(0.0f);
        if (b + 1 < numBands_) {
            lp_[b][0].reset(); lp_[b][1].reset();
            hp_[b][0].reset(); hp_[b][1].reset();
        }
    }
}

} // namespace radiosuite::audio
