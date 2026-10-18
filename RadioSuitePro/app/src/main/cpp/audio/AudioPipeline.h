#pragma once

#include "AudioBlock.h"
#include "AudioProcessor.h"
#include "processors/Agc.h"
#include "processors/ParametricEq.h"
#include "processors/MultibandComp.h"
#include "processors/StereoEnhancer.h"
#include "processors/BrickwallLimiter.h"

#include <atomic>
#include <memory>
#include <vector>
#include <mutex>

namespace radiosuite::audio {

// Estructura completa de parámetros del pipeline, publicada desde la UI/control
// y consumida atómicamente por el hilo de audio.
struct PipelineParameters {
    Agc::Params              agc;
    std::vector<EqBandParams> eqBands;
    std::vector<CompBandParams> compBands;
    StereoEnhancerParams     enhancer;
    BrickwallParams          brickwall;
    float                    outputGainDb = 0.0f; // trims final de la cadena
    float                    sampleRate   = 48000.0f;
};

// Métricas publicadas para la UI (vómetros, reducción de ganancia).
struct PipelineMetrics {
    std::atomic<float> inputPeakDb   { -120.0f };
    std::atomic<float> inputRmsDb    { -120.0f };
    std::atomic<float> outputPeakDb  { -120.0f };
    std::atomic<float> outputRmsDb   { -120.0f };
    std::atomic<float> agcGainDb     { 0.0f };
    std::atomic<float> brickwallGrDb { 0.0f };
};

// AudioPipeline
// -------------
// Orquesta la cadena DSP secuencial de 5 eslabones:
//   AGC -> EQ paramétrico -> Compresor multibanda -> Enhancer estéreo -> Brickwall.
// Diseñado para ser invocado desde el callback de Oboe (hilo de audio de alta
// prioridad). Todas las actualizaciones de parámetros son thread-safe vía un
// puntero atómico a una instantánea inmutable (double-buffered).
class AudioPipeline {
public:
    explicit AudioPipeline(float sampleRate, std::size_t numEqBands = 5,
                           std::size_t numCompBands = 4);
    ~AudioPipeline();

    // Procesa un bloque estéreo in-place. Devuelve false si la cadena debe
    // cortarse (raramente; p.ej. bypass completo).
    bool processBlock(AudioBlock& block);

    // Publica una nueva instantánea de parámetros. Thread-safe respecto al
    // hilo de audio; copia el struct y lo intercambia atómicamente.
    void updateParameters(const PipelineParameters& p);

    // Activa/desactiva la cadena completa (bypass).
    void setEnabled(bool enabled) { enabled_ = enabled; }
    bool enabled() const { return enabled_.load(); }

    // Referencia de solo lectura a las métricas para la UI.
    const PipelineMetrics& metrics() const { return metrics_; }

    void reset();

private:
    std::atomic<bool> enabled_{true};

    // Procesadores propios (orden fijo del pipeline).
    Agc               agc_;
    ParametricEq      eq_;
    MultibandComp     comp_;
    StereoEnhancer    enhancer_;
    BrickwallLimiter  brickwall_;

    // Double-buffer de parámetros: el hilo de audio lee snapshot_, el de
    // control escribe pending_ y luego intercambia con std::atomic::exchange.
    std::mutex paramsMutex_;
    PipelineParameters pending_;
    std::atomic<PipelineParameters*> snapshot_;

    PipelineMetrics metrics_;

    void applySnapshot(const PipelineParameters& p);
    void measureIO(const AudioBlock& block, bool isInput);
};

} // namespace radiosuite::audio
