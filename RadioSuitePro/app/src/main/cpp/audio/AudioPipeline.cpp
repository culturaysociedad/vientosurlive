#include "AudioPipeline.h"

#include <cmath>
#include <algorithm>

namespace radiosuite::audio {

namespace {
inline float dbToGain(float db) { return std::pow(10.0f, db * 0.05f); }
inline float gainToDb(float g)  { return 20.0f * std::log10(g + 1e-12f); }
inline float peakToDb(float p)  { return 20.0f * std::log10(p + 1e-12f); }
} // namespace

AudioPipeline::AudioPipeline(float sampleRate, std::size_t numEqBands,
                             std::size_t numCompBands)
    : agc_(Agc::Params{.sampleRate = sampleRate}),
      eq_(sampleRate, numEqBands),
      comp_(sampleRate, numCompBands),
      enhancer_(sampleRate),
      brickwall_(sampleRate) {
    pending_.sampleRate = sampleRate;
    snapshot_.store(new PipelineParameters(pending_));
}

AudioPipeline::~AudioPipeline() {
    delete snapshot_.exchange(nullptr);
}

bool AudioPipeline::processBlock(AudioBlock& block) {
    if (!enabled_.load(std::memory_order_relaxed)) return true;

    // Intercambia parámetros pendientes si los hay (lock corto, fuera del hot path).
    PipelineParameters* snap = snapshot_.load(std::memory_order_acquire);
    if (snap) applySnapshot(*snap);

    // 1) Medición de entrada (pre-DSP).
    measureIO(block, /*isInput=*/true);

    // 2) Cadena secuencial.
    if (!agc_.process(block))       return false;
    if (!eq_.process(block))        return false;
    if (!comp_.process(block))      return false;
    if (!enhancer_.process(block))  return false;
    if (!brickwall_.process(block)) return false;

    // 3) Ganancia de salida final (trim).
    const float outGain = dbToGain(snap ? snap->outputGainDb : 0.0f);
    if (outGain != 1.0f) {
        for (std::size_t c = 0; c < block.numChannels; ++c) {
            float* d = block.channelData[c];
            for (std::size_t i = 0; i < block.numFrames; ++i) d[i] *= outGain;
        }
    }

    // 4) Métricas de salida (post-DSP) + estados específicos.
    measureIO(block, /*isInput=*/false);
    metrics_.agcGainDb.store(agc_.currentGainDb(), std::memory_order_relaxed);
    metrics_.brickwallGrDb.store(brickwall_.gainReductionDb(),
                                  std::memory_order_relaxed);
    return true;
}

void AudioPipeline::updateParameters(const PipelineParameters& p) {
    std::lock_guard<std::mutex> lk(paramsMutex_);
    pending_ = p;
    // Publica una copia nueva atómicamente; el hilo de audio la aplicará en
    // el siguiente bloque. La anterior se libera aquí.
    PipelineParameters* old = snapshot_.exchange(new PipelineParameters(p));
    delete old;
}

void AudioPipeline::applySnapshot(const PipelineParameters& p) {
    if (p.sampleRate > 0) {
        agc_.setParameters({p.agc.targetLevelDb, p.agc.maxGainDb,
                            p.agc.attackMs, p.agc.releaseMs, p.sampleRate});
        eq_.setSampleRate(p.sampleRate);
        comp_.setSampleRate(p.sampleRate);
        enhancer_.setSampleRate(p.sampleRate);
        brickwall_.setSampleRate(p.sampleRate);
    }
    for (std::size_t i = 0; i < p.eqBands.size() && i < ParametricEq::kMaxBands; ++i)
        eq_.setBand(i, p.eqBands[i]);
    for (std::size_t i = 0; i < p.compBands.size() && i < MultibandComp::kMaxBands; ++i)
        comp_.setBand(i, p.compBands[i]);
    enhancer_.setParameters(p.enhancer);
    brickwall_.setParameters(p.brickwall);
}

void AudioPipeline::measureIO(const AudioBlock& block, bool isInput) {
    float peak = 0.0f, sumSq = 0.0f;
    const std::size_t n = block.numFrames;
    for (std::size_t c = 0; c < block.numChannels; ++c) {
        const float* d = block.channelData[c];
        for (std::size_t i = 0; i < n; ++i) {
            const float s = std::fabs(d[i]);
            if (s > peak) peak = s;
            sumSq += d[i] * d[i];
        }
    }
    const float rms = std::sqrt(sumSq / static_cast<float>(block.totalSamples()));
    const float peakDb = peakToDb(peak);
    const float rmsDb  = peakToDb(rms);
    if (isInput) {
        metrics_.inputPeakDb.store(peakDb, std::memory_order_relaxed);
        metrics_.inputRmsDb.store(rmsDb,  std::memory_order_relaxed);
    } else {
        metrics_.outputPeakDb.store(peakDb, std::memory_order_relaxed);
        metrics_.outputRmsDb.store(rmsDb,  std::memory_order_relaxed);
    }
}

void AudioPipeline::reset() {
    agc_.reset(); eq_.reset(); comp_.reset();
    enhancer_.reset(); brickwall_.reset();
}

} // namespace radiosuite::audio
