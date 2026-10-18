#pragma once

#include <oboe/Oboe.h>
#include "audio/AudioPipeline.h"
#include "player/AudioMixer.h"
#include <functional>
#include <memory>
#include <atomic>

namespace radiosuite::audio {

// OboeRenderer
// ------------
// Salida de audio de baja latencia (AAudio / OpenSL ES). Implementa
// oboe::AudioStreamCallback y alimenta el pipeline DSP con los bloques
// producidos por el mezclador, escribiendo el resultado al dispositivo.
class OboeRenderer : public oboe::AudioStreamCallback {
public:
    OboeRenderer(
        AudioMixer* mixer,
        AudioPipeline* pipeline,
        std::function<void(const float*, const float*, std::size_t)> outputCallback);
    ~OboeRenderer();

    bool start();
    void stop();

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

    void onErrorBeforeClose(oboe::AudioStream* stream, oboe::Result error) override;
    void onErrorAfterClose(oboe::AudioStream* stream,  oboe::Result error) override;

private:
    AudioMixer*  mixer_;
    AudioPipeline* pipeline_;
    std::function<void(const float*, const float*, std::size_t)> outputCallback_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<bool> running_{false};

    // Scratch buffers estéreo para el callback (asignados en start()).
    std::vector<float> scratchL_, scratchR_;
};

} // namespace radiosuite::audio
