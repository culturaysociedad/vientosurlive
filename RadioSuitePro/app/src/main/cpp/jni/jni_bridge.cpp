#include "NativeEngine.h"
#include <jni.h>
#include <android/log.h>
#include <memory>
#include <unordered_map>
#include <mutex>
#include <algorithm>
#include <limits>

#define LOG_TAG "RadioSuiteJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace radiosuite;

namespace {
// Registro global de instancias de NativeEngine por jlong handle.
// En una app real esto vive en el contexto del Foreground Service.
std::mutex g_engineMutex;
std::unordered_map<jlong, std::unique_ptr<jni::NativeEngine>> g_engines;
jlong g_nextHandle = 1;
JavaVM* g_javaVm = nullptr;

struct PacketListener {
    jobject callback;
    jmethodID method;
};
std::mutex g_packetMutex;
std::unordered_map<jlong, PacketListener> g_packetListeners;

void removePacketListener(JNIEnv* env, jlong handle) {
    jobject callback = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_packetMutex);
        const auto it = g_packetListeners.find(handle);
        if (it != g_packetListeners.end()) {
            callback = it->second.callback;
            g_packetListeners.erase(it);
        }
    }
    if (callback) env->DeleteGlobalRef(callback);
}

void deliverPacket(jlong handle, const uint8_t* data, std::size_t length) {
    PacketListener listener{};
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    {
        std::lock_guard<std::mutex> lock(g_packetMutex);
        const auto it = g_packetListeners.find(handle);
        if (it != g_packetListeners.end()) {
            listener = it->second;
            listener.callback = env->NewLocalRef(it->second.callback);
        }
    }
    if (!listener.callback) {
        if (attached) g_javaVm->DetachCurrentThread();
        return;
    }

    const jsize packetSize = static_cast<jsize>(std::min<std::size_t>(
        length, static_cast<std::size_t>(std::numeric_limits<jsize>::max())));
    jbyteArray packet = env->NewByteArray(packetSize);
    if (packet) {
        env->SetByteArrayRegion(packet, 0, packetSize, reinterpret_cast<const jbyte*>(data));
        env->CallVoidMethod(listener.callback, listener.method, packet);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(packet);
    }
    env->DeleteLocalRef(listener.callback);
    if (attached) g_javaVm->DetachCurrentThread();
}

jlong createEngine(JNIEnv*) {
    std::lock_guard<std::mutex> lk(g_engineMutex);
    jlong h = g_nextHandle++;
    g_engines[h] = std::make_unique<jni::NativeEngine>();
    return h;
}

jni::NativeEngine* engine(JNIEnv* env, jlong handle) {
    std::lock_guard<std::mutex> lk(g_engineMutex);
    auto it = g_engines.find(handle);
    return (it != g_engines.end()) ? it->second.get() : nullptr;
}

void destroyEngine(JNIEnv* env, jlong handle) {
    removePacketListener(env, handle);
    std::lock_guard<std::mutex> lk(g_engineMutex);
    g_engines.erase(handle);
}
} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_javaVm = vm;
    return JNI_VERSION_1_6;
}

// ---------------- Engine lifecycle ----------------
JNIEXPORT jlong JNICALL
Java_com_radiosuite_pro_dsp_DspEngine_nativeCreate(JNIEnv* env, jclass) {
    return createEngine(env);
}

JNIEXPORT void JNICALL
Java_com_radiosuite_pro_dsp_DspEngine_nativeDestroy(JNIEnv* env, jclass, jlong handle) {
    destroyEngine(env, handle);
}

JNIEXPORT jboolean JNICALL
Java_com_radiosuite_pro_dsp_DspEngine_nativeStart(JNIEnv* env, jclass, jlong handle) {
    auto* e = engine(env, handle);
    return e ? static_cast<jboolean>(e->start()) : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_radiosuite_pro_dsp_DspEngine_nativeStop(JNIEnv* env, jclass, jlong handle) {
    auto* e = engine(env, handle);
    if (e) e->stop();
}

JNIEXPORT void JNICALL
Java_com_radiosuite_pro_dsp_DspEngine_nativeSetSourceGain(
    JNIEnv* env, jclass, jlong handle, jint sourceId, jfloat gainDb) {
    auto* e = engine(env, handle);
    if (e && sourceId >= 0) e->setSourceGain(static_cast<std::size_t>(sourceId), gainDb);
}

JNIEXPORT void JNICALL
Java_com_radiosuite_pro_dsp_DspEngine_nativePushInputPcm(
    JNIEnv* env, jclass, jlong handle, jfloatArray samples, jint channels, jint frames) {
    auto* e = engine(env, handle);
    if (!e || !samples || channels <= 0 || frames <= 0) return;
    const jsize count = env->GetArrayLength(samples);
    if (frames > count / channels) return;
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (!data) return;
    e->pushInputPcm(data, static_cast<std::size_t>(frames), channels);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_radiosuite_pro_dsp_DspEngine_nativePushPlayerPcm(
    JNIEnv* env, jclass, jlong handle, jfloatArray samples, jint channels, jint frames) {
    auto* e = engine(env, handle);
    if (!e || !samples || channels <= 0 || frames <= 0) return;
    const jsize count = env->GetArrayLength(samples);
    if (frames > count / channels) return;
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (!data) return;
    e->pushPlayerInputPcm(data, static_cast<std::size_t>(frames), channels);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

// ---------------- DSP parameters ----------------
JNIEXPORT void JNICALL
Java_com_radiosuite_pro_dsp_DspEngine_nativeUpdatePipeline(
    JNIEnv* env, jclass, jlong handle,
    jfloat targetDb, jfloat maxGainDb, jfloat agcAttack, jfloat agcRelease,
    jfloat outGainDb, jboolean enabled) {
    auto* e = engine(env, handle);
    if (!e) return;
    audio::PipelineParameters p{};
    p.agc.targetLevelDb = targetDb;
    p.agc.maxGainDb     = maxGainDb;
    p.agc.attackMs      = agcAttack;
    p.agc.releaseMs     = agcRelease;
    p.outputGainDb      = outGainDb;
    e->updatePipelineParameters(p);
    e->setPipelineEnabled(enabled == JNI_TRUE);
}

// ---------------- Metrics (polling desde UI) ----------------
JNIEXPORT jfloat JNICALL
Java_com_radiosuite_pro_dsp_DspEngine_nativeInputPeakDb(JNIEnv* env, jclass, jlong h) {
    auto* e = engine(env, h);
    return e ? e->metrics().inputPeakDb.load() : -120.0f;
}
JNIEXPORT jfloat JNICALL
Java_com_radiosuite_pro_dsp_DspEngine_nativeOutputPeakDb(JNIEnv* env, jclass, jlong h) {
    auto* e = engine(env, h);
    return e ? e->metrics().outputPeakDb.load() : -120.0f;
}
JNIEXPORT jfloat JNICALL
Java_com_radiosuite_pro_dsp_DspEngine_nativeOutputRmsDb(JNIEnv* env, jclass, jlong h) {
    auto* e = engine(env, h);
    return e ? e->metrics().outputRmsDb.load() : -120.0f;
}
JNIEXPORT jfloat JNICALL
Java_com_radiosuite_pro_dsp_DspEngine_nativeAgcGainDb(JNIEnv* env, jclass, jlong h) {
    auto* e = engine(env, h);
    return e ? e->metrics().agcGainDb.load() : 0.0f;
}
JNIEXPORT jfloat JNICALL
Java_com_radiosuite_pro_dsp_DspEngine_nativeBrickwallGrDb(JNIEnv* env, jclass, jlong h) {
    auto* e = engine(env, h);
    return e ? e->metrics().brickwallGrDb.load() : 0.0f;
}

// ---------------- Streaming ----------------
JNIEXPORT jboolean JNICALL
Java_com_radiosuite_pro_stream_StreamEncoder_nativeStartEncoder(
    JNIEnv* env, jclass, jlong handle, jint codec, jint sampleRate,
    jint channels, jint bitrate, jobject callback) {
    auto* e = engine(env, handle);
    if (!e || !callback) return JNI_FALSE;
    audio::EncoderParams p;
    p.codec      = static_cast<audio::StreamCodec>(codec);
    p.sampleRate = sampleRate;
    p.channels   = channels;
    p.bitrate    = bitrate;
    e->setEncoderParams(p);
    removePacketListener(env, handle);
    jobject globalCallback = env->NewGlobalRef(callback);
    if (!globalCallback) return JNI_FALSE;
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID method = env->GetMethodID(callbackClass, "onNativePacket", "([B)V");
    env->DeleteLocalRef(callbackClass);
    if (!method) {
        env->DeleteGlobalRef(globalCallback);
        return JNI_FALSE;
    }
    {
        std::lock_guard<std::mutex> lock(g_packetMutex);
        g_packetListeners[handle] = {globalCallback, method};
    }
    const bool started = e->startEncoder([handle](const uint8_t* data, std::size_t length) {
        deliverPacket(handle, data, length);
    });
    if (!started) removePacketListener(env, handle);
    return static_cast<jboolean>(started);
}

JNIEXPORT void JNICALL
Java_com_radiosuite_pro_stream_StreamEncoder_nativeStopEncoder(
    JNIEnv* env, jclass, jlong handle) {
    auto* e = engine(env, handle);
    if (e) e->stopEncoder();
    removePacketListener(env, handle);
}

} // extern "C"
