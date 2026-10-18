#include "NativeEngine.h"
#include <android/log.h>
#include <algorithm>
#include <utility>

#define LOG_TAG "NativeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace radiosuite::jni {

class PcmSource final : public audio::AudioSource {
public:
    bool render(float* left, float* right, std::size_t frames) override {
        readBuffer_.resize(frames * 2);
        const std::size_t samplesRead = samples_.read(readBuffer_.data(), frames * 2);
        const std::size_t framesRead = samplesRead / 2;
        for (std::size_t frame = 0; frame < framesRead; ++frame) {
            left[frame] = readBuffer_[frame * 2];
            right[frame] = readBuffer_[frame * 2 + 1];
        }
        std::fill(left + framesRead, left + frames, 0.0f);
        std::fill(right + framesRead, right + frames, 0.0f);
        return true;
    }

    bool active() const override { return true; }

    void write(const float* samples, std::size_t frames, int channels) {
        writeBuffer_.resize(frames * 2);
        for (std::size_t frame = 0; frame < frames; ++frame) {
            writeBuffer_[frame * 2] = samples[frame * channels];
            writeBuffer_[frame * 2 + 1] = channels > 1 ? samples[frame * channels + 1] : writeBuffer_[frame * 2];
        }
        samples_.write(writeBuffer_.data(), frames * 2);
    }

private:
    audio::RingBuffer samples_{65536};
    std::vector<float> readBuffer_;
    std::vector<float> writeBuffer_;
};

NativeEngine::NativeEngine() {
    const float sr = 48000.0f;
    pipeline_  = std::make_unique<audio::AudioPipeline>(sr, 5, 4);
    mixer_     = std::make_unique<audio::AudioMixer>(sr);
    playerSource_ = std::make_unique<PcmSource>();
    microphoneSource_ = std::make_unique<PcmSource>();
    mixer_->setSource(0, playerSource_.get(), 0.0f);
    mixer_->setSource(2, microphoneSource_.get(), 0.0f);
    renderer_  = std::make_unique<audio::OboeRenderer>(
        mixer_.get(), pipeline_.get(),
        [this](const float* left, const float* right, std::size_t frames) {
            pushPlayerPcm(left, right, frames);
        });
    LOGI("NativeEngine construido (sr=%.0f)", sr);
}

NativeEngine::~NativeEngine() {
    stop();
    LOGI("NativeEngine destruido");
}

bool NativeEngine::start() {
    if (!renderer_->start()) { LOGE("OboeRenderer no arrancó"); return false; }
    LOGI("Motor nativo iniciado");
    return true;
}

void NativeEngine::stop() {
    stopEncoder();
    renderer_->stop();
}

void NativeEngine::updatePipelineParameters(const audio::PipelineParameters& p) {
    pipeline_->updateParameters(p);
}

void NativeEngine::setPipelineEnabled(bool enabled) {
    pipeline_->setEnabled(enabled);
}

const audio::PipelineMetrics& NativeEngine::metrics() const {
    return pipeline_->metrics();
}

void NativeEngine::setSourceGain(std::size_t id, float gainDb) {
    mixer_->setSourceGain(id, gainDb);
}

void NativeEngine::startCrossfade(float durationMs) {
    mixer_->startCrossfade(durationMs);
}

void NativeEngine::pushInputPcm(const float* samples, std::size_t frames, int channels) {
    microphoneSource_->write(samples, frames, channels);
}

void NativeEngine::pushPlayerInputPcm(const float* samples, std::size_t frames, int channels) {
    playerSource_->write(samples, frames, channels);
}

void NativeEngine::setEncoderParams(const audio::EncoderParams& p) {
    stopEncoder();
    std::atomic_store(&encoder_, std::make_shared<audio::IcecastEncoder>(p));
}

bool NativeEngine::startEncoder(audio::PacketCallback cb) {
    const auto encoder = std::atomic_load(&encoder_);
    if (!encoder) {
        LOGE("setEncoderParams() no llamado antes de startEncoder()");
        return false;
    }
    return encoder->start(std::move(cb));
}

void NativeEngine::stopEncoder() {
    auto encoder = std::atomic_load(&encoder_);
    std::atomic_store(&encoder_, std::shared_ptr<audio::IcecastEncoder>{});
    if (encoder) encoder->stop();
}

void NativeEngine::pushPlayerPcm(const float* left, const float* right, std::size_t n) {
    const auto encoder = std::atomic_load(&encoder_);
    if (encoder) encoder->pushSamples(left, right, n);
}

} // namespace radiosuite::jni
