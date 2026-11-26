// Realtime 24kHz Stretcher / Signalsmith engine adapter for UtaMaru.
// JNI methods intentionally carry 24 suffix and a new .so name to avoid stale JNI ABI.
#include <jni.h>
#include <android/log.h>
#include <rubberband/RubberBandStretcher.h>
#include "signalsmith-stretch.h"
#include "signalsmith_harmonics24.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <new>
#include <stdexcept>

namespace {
constexpr int kFrame = 480;
constexpr int kDelay = 4800;   // 200ms at 24kHz, before 48kHz output conversion
constexpr int kRing = 65536;
using RubberBand::RubberBandStretcher;

struct Engine {
    virtual ~Engine() = default;
    virtual void feed(const float *in, double ratio, float *out) = 0;
};

struct RubberEngine final : Engine {
    static constexpr int options = RubberBandStretcher::OptionProcessRealTime |
        RubberBandStretcher::OptionEngineFiner |
        RubberBandStretcher::OptionWindowStandard |
        RubberBandStretcher::OptionFormantPreserved |
        RubberBandStretcher::OptionPitchHighConsistency;
    RubberBandStretcher stretcher{24000, 1, options, 1.0, 1.0};
    std::array<float, kRing> fifo{};
    std::array<float, 8192> retrieve{};
    int read = 0, write = 0, queued = 0;
    int startupDrop = 0;
    int remainingDelay = kDelay;

    RubberEngine() {
        stretcher.setMaxProcessSize(kFrame);
        startupDrop = static_cast<int>(stretcher.getStartDelay());
        const size_t pad = stretcher.getPreferredStartPad();
        if (pad > 24000 || startupDrop < 0 || startupDrop >= kDelay)
            throw std::runtime_error("unexpected Rubber Band startup delay");
        std::array<float, kFrame> silence{};
        for (size_t at = 0; at < pad; at += kFrame)
            processOne(silence.data(), std::min(size_t(kFrame), pad - at));
    }
    void enqueue(float value) {
        if (queued == kRing) throw std::runtime_error("Stretcher output ring overflow");
        fifo[write] = value;
        write = (write + 1) % kRing;
        ++queued;
    }
    void processOne(const float *in, size_t count) {
        const float *channels[] = {in};
        stretcher.process(channels, count, false);
        int available = stretcher.available();
        if (available < 0) throw std::runtime_error("Stretcher error");
        while (available > 0) {
            size_t take = std::min(size_t(available), retrieve.size());
            float *output[] = {retrieve.data()};
            const auto got = stretcher.retrieve(output, take);
            if (got == 0) throw std::runtime_error("Stretcher retrieve failed");
            for (size_t i = 0; i < got; ++i) {
                if (startupDrop > 0) --startupDrop;
                else enqueue(retrieve[i]);
            }
            available -= static_cast<int>(got);
        }
    }
    void feed(const float *in, double ratio, float *out) override {
        stretcher.setPitchScale(ratio);
        processOne(in, kFrame);
        for (int i = 0; i < kFrame; ++i) {
            if (remainingDelay > 0) { out[i] = 0.f; --remainingDelay; }
            else if (queued > 0) {
                out[i] = fifo[read]; read = (read + 1) % kRing; --queued;
            } else {
                // A genuine Stretcher underrun must not create a sudden dry-voice fallback.
                out[i] = 0.f;
            }
        }
    }
};

struct SignalsmithEngine final : Engine {
    signalsmith::stretch::SignalsmithStretch<float> stretch;
    std::array<float, kRing> fifo{};
    int read = 0, write = 0, queued = 0;
    int startupDrop = 0;
    int remainingDelay = kDelay;
    const bool formantEnabled;
    const bool harmonicsEnabled;
    utamaru::Harmonics24 exciter;
    explicit SignalsmithEngine(int variant)
        : formantEnabled(variant >= 2), harmonicsEnabled(variant >= 3) {
        stretch.presetDefault(1, 24000.f);
        stretch.setTransposeFactor(1.f);
        if (formantEnabled) {
            // Preserve formants relative to the transposition.
            // IMPORTANT: base=0 enables Signalsmith's internal F0 estimator.
            // When audio resumes after silence, that estimator can have no reliable
            // voiced spectrum yet.  Use a stable nominal 200 Hz base (specified
            // as a fraction of the 24 kHz sample rate, per Signalsmith's docs).
            // This is an onset-dropout A/B fix, not proof of the root cause.
            stretch.setFormantFactor(1.f, true);
            stretch.setFormantBase(200.f / 24000.f);
        }
        startupDrop = stretch.inputLatency() + stretch.outputLatency();
        if (startupDrop < 0 || startupDrop >= kDelay)
            throw std::runtime_error("unexpected Signalsmith latency");
    }
    void enqueue(float value) {
        if (queued == kRing) throw std::runtime_error("Signalsmith output ring overflow");
        fifo[write] = value; write = (write + 1) % kRing; ++queued;
    }
    void feed(const float *in, double ratio, float *out) override {
        stretch.setTransposeFactor(static_cast<float>(ratio));
        std::array<float, kFrame> output{};
        // API accepts mutable channel pointer arrays; input contents are not modified.
        float inputCopy[kFrame];
        std::copy(in, in + kFrame, inputCopy);
        float *inputs[] = {inputCopy}, *outputs[] = {output.data()};
        stretch.process(inputs, kFrame, outputs, kFrame);
        for (float value : output) {
            if (startupDrop > 0) --startupDrop;
            else enqueue(value);
        }
        for (int i = 0; i < kFrame; ++i) {
            if (remainingDelay > 0) { out[i] = 0.f; --remainingDelay; }
            else if (queued > 0) {
                out[i] = fifo[read]; read = (read + 1) % kRing; --queued;
            } else out[i] = 0.f;
            if (harmonicsEnabled) out[i] = exciter.process(out[i], ratio);
        }
    }
};

std::unique_ptr<Engine> create(int id) {
    if (id == 0) return std::make_unique<RubberEngine>();
    if (id >= 1 && id <= 3) return std::make_unique<SignalsmithEngine>(id);
    throw std::runtime_error("unknown engine");
}
} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_org_example_voicepitchlooper_PitchEngine24Native_nativeCapabilities24V2(
    JNIEnv *, jobject) {
    // IDs 0=RubberBand, 1=Signalsmith normal, 2=formant, 3=formant+harmonics.
    return 0x0f;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_example_voicepitchlooper_PitchEngine24Native_nativeCreate24V2(
    JNIEnv *, jobject, jint engineId) {
    try { return reinterpret_cast<jlong>(create(engineId).release()); }
    catch (const std::exception &e) {
        __android_log_print(ANDROID_LOG_ERROR, "UtaMaruPitch24", "native create failed for id=%d: %s", int(engineId), e.what());
        return 0;
    }
    catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, "UtaMaruPitch24", "native create failed for id=%d: unknown exception", int(engineId));
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_example_voicepitchlooper_PitchEngine24Native_nativeProcess24V2(
    JNIEnv *env, jobject, jlong handle, jshortArray input, jdouble ratio, jshortArray output) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (!engine || !input || !output || env->GetArrayLength(input) != kFrame ||
        env->GetArrayLength(output) != kFrame) return -1;
    if (!std::isfinite(ratio)) ratio = 1.0;
    ratio = std::clamp(double(ratio), 0.3, 10.0);
    std::array<jshort, kFrame> raw{};
    std::array<float, kFrame> in{}, out{};
    env->GetShortArrayRegion(input, 0, kFrame, raw.data());
    if (env->ExceptionCheck()) return -2;
    for (int i=0; i < kFrame; ++i) in[i] = raw[i]/32768.f;
    try { engine->feed(in.data(), ratio, out.data()); }
    catch (...) { return -3; }
    for (int i=0; i < kFrame; ++i) {
        const float value = std::isfinite(out[i]) ? std::clamp(out[i], -1.f, 1.f) : 0.f;
        raw[i] = static_cast<jshort>(std::lrint(value * 32767.f));
    }
    env->SetShortArrayRegion(output, 0, kFrame, raw.data());
    return env->ExceptionCheck() ? -4 : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_org_example_voicepitchlooper_PitchEngine24Native_nativeDestroy24V2(
    JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Engine *>(handle);
}
