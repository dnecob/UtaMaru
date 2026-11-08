// JNI adapter for Rubber Band 4.0 LiveShifter. UtaMaru integration code.
// Calls happen on the single AudioEngine worker thread; not concurrently.
#include <jni.h>
#include <rubberband/RubberBandLiveShifter.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <new>
#include <vector>

namespace {
using RubberBand::RubberBandLiveShifter;
constexpr int kMaxInputFrames = 960;

struct ShifterState {
    RubberBandLiveShifter shifter;
    size_t block;
    std::vector<float> blockIn;
    std::vector<float> blockOut;
    std::vector<float> fifo;
    size_t filled = 0;
    size_t head = 0;
    size_t tail = 0;
    size_t queued = 0;

    explicit ShifterState(int rate)
        : shifter(rate, 1,
                  RubberBandLiveShifter::OptionWindowShort |
                  RubberBandLiveShifter::OptionFormantPreserved),
          block(shifter.getBlockSize()),
          blockIn(block), blockOut(block), fifo(block * 2 + kMaxInputFrames * 2, 0.f) {
        if (block == 0 || block > 32768) throw std::bad_alloc();
        shifter.setDebugLevel(0);
        shifter.setPitchScale(1.0);
    }

    void push(float v) {
        if (queued == fifo.size()) { // Recover conservatively on unexpected overflow.
            tail = (tail + 1) % fifo.size();
            --queued;
        }
        fifo[head] = v;
        head = (head + 1) % fifo.size();
        ++queued;
    }
    float pop() {
        if (queued == 0) return 0.f; // Accumulating first fixed-size block.
        float v = fifo[tail];
        tail = (tail + 1) % fifo.size();
        --queued;
        return v;
    }

    void process(const jshort* input, jshort* output, int size, double ratio) {
        if (!std::isfinite(ratio)) ratio = 1.0;
        ratio = std::max(0.3, std::min(10.0, ratio));
        for (int i = 0; i < size; ++i) {
            blockIn[filled++] = static_cast<float>(input[i]) / 32768.f;
            if (filled == block) {
                shifter.setPitchScale(ratio);
                const float* in[] = { blockIn.data() };
                float* out[] = { blockOut.data() };
                shifter.shift(in, out);
                for (size_t n = 0; n < block; ++n) push(blockOut[n]);
                filled = 0;
            }
            const float v = std::max(-1.f, std::min(1.f, pop()));
            output[i] = static_cast<jshort>(std::lrint(v * 32767.f));
        }
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_example_voicepitchlooper_RubberBandNative_nativeCreate(
    JNIEnv*, jobject, jint rate) {
    if (rate < 8000 || rate > 192000) return 0;
    try {
        return reinterpret_cast<jlong>(new ShifterState(rate));
    } catch (...) { return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_example_voicepitchlooper_RubberBandNative_nativeProcess(
    JNIEnv* env, jobject, jlong handle, jshortArray input, jint length,
    jdouble ratio, jshortArray output) {
    auto* s = reinterpret_cast<ShifterState*>(handle);
    if (!s || length < 0 || length > kMaxInputFrames ||
        env->GetArrayLength(input) < length || env->GetArrayLength(output) < length) return -1;
    std::array<jshort, kMaxInputFrames> in{};
    std::array<jshort, kMaxInputFrames> out{};
    env->GetShortArrayRegion(input, 0, length, in.data());
    if (env->ExceptionCheck()) return -2;
    try { s->process(in.data(), out.data(), length, ratio); }
    catch (...) { return -3; }
    env->SetShortArrayRegion(output, 0, length, out.data());
    return env->ExceptionCheck() ? -4 : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_org_example_voicepitchlooper_RubberBandNative_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<ShifterState*>(handle);
}
