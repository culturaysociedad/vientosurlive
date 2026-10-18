#pragma once

#include "RingBuffer.h"
#include <atomic>
#include <functional>
#include <memory>
#include <thread>
#include <vector>

namespace radiosuite::audio {

enum class StreamCodec { Opus, Vorbis, Mp3 };

struct EncoderParams {
    StreamCodec codec     = StreamCodec::Opus;
    int         sampleRate = 48000;
    int         channels   = 2;
    int         bitrate    = 128;        // kbps
    float       quality    = 0.7f;       // 0..1 (Vorbis)
};

// Callback que entrega paquetes codificados listos para enviar al servidor.
using PacketCallback = std::function<void(const uint8_t* data, std::size_t len)>;

// IcecastEncoder
// --------------
// Consume muestras float estéreo del RingBuffer (alimentado por el hilo de
// audio post-pipeline), las codifica en vivo y entrega paquetes OGG/MP3
// listos para su envío por HTTP a Icecast/Shoutcast.
//
// La implementación concreta (IcecastEncoder.cpp) enlaza contra libopus,
// libvorbisenc, libogg y libmp3lame según `codec`.
class IcecastEncoder {
public:
    explicit IcecastEncoder(const EncoderParams& p);
    ~IcecastEncoder();

    bool start(PacketCallback cb);
    void stop();

    // Hilo de audio escribe aquí (post-pipeline, post-brickwall).
    void pushSamples(const float* left, const float* right, std::size_t n);

    // Ejecuta el bucle de codificación en el hilo de red. Bloqueante.
    void runEncodeLoop();

    bool isRunning() const { return running_.load(); }

private:
    EncoderParams params_;
    std::atomic<bool> running_{false};
    PacketCallback packetCallback_;
    RingBuffer ringL_, ringR_;
    std::vector<uint8_t> packetBuf_;
    std::thread encodeThread_;

    bool initEncoder();
    void destroyEncoder();
    // Punteros opacos a los handle de cada librería (definidos en .cpp).
    void* encoderHandle_ = nullptr;
};

} // namespace radiosuite::audio
