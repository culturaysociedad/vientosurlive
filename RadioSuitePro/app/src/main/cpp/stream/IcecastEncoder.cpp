#include "IcecastEncoder.h"
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <thread>
#include <chrono>

// Cabeceras de los códecs (en build real se enlazan como static libs).
#include <opus.h>
#include <vorbis/vorbisenc.h>
#include <ogg/ogg.h>
#include <lame.h>

#define LOG_TAG "IcecastEncoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace radiosuite::audio {

namespace {
constexpr std::size_t kRingSeconds = 4; // buffer de red resiliente
}

IcecastEncoder::IcecastEncoder(const EncoderParams& p)
    : params_(p),
      ringL_(kRingSeconds * p.sampleRate),
      ringR_(kRingSeconds * p.sampleRate) {
    packetBuf_.assign(8192, 0);
}

IcecastEncoder::~IcecastEncoder() {
    stop();
    destroyEncoder();
}

bool IcecastEncoder::initEncoder() {
    switch (params_.codec) {
        case StreamCodec::Opus: {
            int err = 0;
            OpusEncoder* enc = opus_encoder_create(params_.sampleRate,
                params_.channels, OPUS_APPLICATION_AUDIO, &err);
            if (err != OPUS_OK || !enc) {
                LOGE("opus_encoder_create falló: %d", err);
                return false;
            }
            opus_encoder_ctl(enc, OPUS_SET_BITRATE(params_.bitrate * 1000));
            encoderHandle_ = enc;
            LOGI("Encoder Opus: %dkbps, %dch, %dHz",
                 params_.bitrate, params_.channels, params_.sampleRate);
            return true;
        }
        case StreamCodec::Vorbis: {
            vorbis_info* vi = new vorbis_info();
            vorbis_info_init(vi);
            int ret = vorbis_encode_init(vi, params_.channels,
                params_.sampleRate, -1, params_.bitrate * 1000, -1);
            if (ret) {
                LOGE("vorbis_encode_init falló: %d", ret);
                vorbis_info_clear(vi);
                delete vi;
                return false;
            }
            encoderHandle_ = vi;
            return true;
        }
        case StreamCodec::Mp3: {
            lame_t lame = lame_init();
            if (!lame) { LOGE("lame_init falló"); return false; }
            lame_set_in_samplerate(lame, params_.sampleRate);
            lame_set_num_channels(lame, params_.channels);
            lame_set_brate(lame, params_.bitrate);
            lame_set_quality(lame, 2); // 0=mejor, 9=peor
            if (lame_init_params(lame) < 0) {
                LOGE("lame_init_params falló");
                lame_close(lame);
                return false;
            }
            encoderHandle_ = lame;
            LOGI("Encoder MP3: %dkbps", params_.bitrate);
            return true;
        }
    }
    return false;
}

void IcecastEncoder::destroyEncoder() {
    if (!encoderHandle_) return;
    switch (params_.codec) {
        case StreamCodec::Opus:   opus_encoder_destroy(static_cast<OpusEncoder*>(encoderHandle_)); break;
        case StreamCodec::Vorbis: {
            vorbis_info* vi = static_cast<vorbis_info*>(encoderHandle_);
            vorbis_info_clear(vi); delete vi; break;
        }
        case StreamCodec::Mp3:    lame_close(static_cast<lame_global_flags*>(encoderHandle_)); break;
    }
    encoderHandle_ = nullptr;
}

bool IcecastEncoder::start(PacketCallback cb) {
    if (running_.load()) return true;
    packetCallback_ = cb;
    if (!initEncoder()) return false;
    running_.store(true);
    encodeThread_ = std::thread([this]() { runEncodeLoop(); });
    return true;
}

void IcecastEncoder::stop() {
    running_.store(false);
    if (encodeThread_.joinable() && encodeThread_.get_id() != std::this_thread::get_id()) {
        encodeThread_.join();
    }
    destroyEncoder();
}

void IcecastEncoder::pushSamples(const float* left, const float* right, std::size_t n) {
    ringL_.write(left, n);
    if (params_.channels > 1) ringR_.write(right, n);
}

void IcecastEncoder::runEncodeLoop() {
    // Frame de codificación típico: 20 ms para Opus, 1024 para Vorbis/MP3.
    const std::size_t frameSize = (params_.codec == StreamCodec::Opus)
        ? static_cast<std::size_t>(params_.sampleRate / 50) // 20 ms
        : 1024;

    std::vector<float> bufL(frameSize, 0.0f), bufR(frameSize, 0.0f);
    std::vector<float> interleaved(frameSize * 2, 0.0f);
    std::vector<uint8_t> encOut(8192, 0);

    while (running_.load()) {
        // Espera a tener muestras suficientes.
        if (ringL_.available() < frameSize ||
            (params_.channels > 1 && ringR_.available() < frameSize)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }
        const std::size_t gotL = ringL_.read(bufL.data(), frameSize);
        const std::size_t gotR = (params_.channels > 1)
            ? ringR_.read(bufR.data(), frameSize) : gotL;
        if (gotL == 0) continue;

        int encoded = 0;
        switch (params_.codec) {
            case StreamCodec::Opus: {
                // Opus espera interleaved float.
                for (std::size_t i = 0; i < gotL; ++i) {
                    interleaved[i * 2]     = bufL[i];
                    interleaved[i * 2 + 1] = bufR[i];
                }
                encoded = opus_encode_float(
                    static_cast<OpusEncoder*>(encoderHandle_),
                    interleaved.data(), static_cast<int>(gotL),
                    encOut.data(), static_cast<opus_int32>(encOut.size()));
                if (encoded < 0) {
                    LOGE("opus_encode_float: %d", encoded);
                    continue;
                }
                break;
            }
            case StreamCodec::Vorbis: {
                // Vorbis necesita dsp/vorbis-dsp-state + ogg packaging.
                // Aquí se delega a una codificación por bloque simplificada.
                // (Implementación completa en el módulo de empaquetado OGG.)
                encoded = 0; // placeholder; el flujo OGG real se maneja en el encoder.
                break;
            }
            case StreamCodec::Mp3: {
                encoded = lame_encode_buffer_ieee_float(
                    static_cast<lame_global_flags*>(encoderHandle_),
                    bufL.data(), bufR.data(), static_cast<int>(gotL),
                    encOut.data(), static_cast<int>(encOut.size()));
                if (encoded < 0) {
                    LOGE("lame_encode: %d", encoded);
                    continue;
                }
                break;
            }
        }

        if (encoded > 0 && packetCallback_) {
            packetCallback_(encOut.data(), static_cast<std::size_t>(encoded));
        }
    }
    LOGI("Bucle de codificación finalizado");
}

} // namespace radiosuite::audio
