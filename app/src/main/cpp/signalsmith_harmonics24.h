#pragma once
// Controlled high-harmonic exciter for Signalsmith's *processed* 24 kHz output.
// The base audio never passes through a saturator: add ONLY a small high-passed,
// anti-aliased nonlinear component. No microphone/raw audio is ever mixed.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>

namespace utamaru {
class Harmonics24 {
    static constexpr int kTaps = 33;          // 2x oversampled 48 kHz anti-alias FIR
    static constexpr int kWetDelay = 8;       // align unmodified wet audio to FIR
    static constexpr float kPi = 3.14159265358979323846f;
    std::array<float, kTaps> filter{};
    std::array<float, kTaps> history{};
    std::array<float, kWetDelay> wetHistory{};
    int writeIndex = 0, wetIndex = 0;
    float prevWet = 0;
    float levelPower = 0;
    float mix = 0;
    // 2-pole Butterworth HP at 1.8 kHz / 24 kHz for upper harmonics.
    float hpB0{}, hpB1{}, hpB2{}, hpA1{}, hpA2{};
    float hpX1=0, hpX2=0, hpY1=0, hpY2=0;

    void firPush(float x) {
        history[writeIndex] = x;
        if (++writeIndex == kTaps) writeIndex = 0;
    }
    float firRead() const {
        float y = 0;
        for (int tap=0; tap<kTaps; ++tap) {
            int index = writeIndex - 1 - tap;
            if (index < 0) index += kTaps;
            y += filter[tap]*history[index];
        }
        return y;
    }
    float highpass(float x) {
        float y = hpB0*x + hpB1*hpX1 + hpB2*hpX2 - hpA1*hpY1 - hpA2*hpY2;
        hpX2 = hpX1; hpX1 = x; hpY2 = hpY1; hpY1 = y;
        return y;
    }
    static float shape(float input) {
        // Even and odd multiples of the *already pitch-shifted* fundamental.
        // Low frequencies are discarded by highpass(); no fullband distortion.
        const float x = std::clamp(input, -0.95f, 0.95f);
        return 0.70f*x*x + 0.25f*(std::tanh(4.0f*x) - 4.0f*x);
    }
public:
    Harmonics24() {
        float sum = 0;
        const float cutoff = 10000.f/48000.f; // avoid >12 kHz folding on 24 kHz output
        for (int i=0; i<kTaps; ++i) {
            const int offset = i - (kTaps-1)/2;
            const float sinc = offset == 0 ? 2*cutoff :
                std::sin(2*kPi*cutoff*offset)/(kPi*offset);
            const float win = 0.5f - 0.5f*std::cos(2*kPi*i/(kTaps-1));
            filter[i] = sinc*win; sum += filter[i];
        }
        for (float &v : filter) v /= sum;

        const float w = 2*kPi*1800.f/24000.f;
        const float cs = std::cos(w), sn = std::sin(w);
        const float a = sn/(2*0.70710678f);
        const float a0 = 1 + a;
        hpB0 = ((1+cs)/2)/a0;
        hpB1 = -(1+cs)/a0;
        hpB2 = hpB0;
        hpA1 = -2*cs/a0;
        hpA2 = (1-a)/a0;
    }

    float process(float wet, double ratio) {
        // Smooth, peak-bounded drive normalizes for speaking volume without
        // changing the base wet signal or final app output volume settings.
        levelPower = 0.999f*levelPower + 0.001f*wet*wet;
        const float rms = std::sqrt(levelPower);
        const float drive = std::clamp(0.20f/std::max(rms, 0.025f), 0.8f, 3.0f);
        const float prev = prevWet;
        prevWet = wet;
        // Generate in 2x time domain (48 kHz) and FIR lowpass before downsampling.
        firPush(shape((prev + wet)*0.5f*drive));
        firPush(shape(wet*drive));
        const float harmonics = highpass(firRead());

        const float safeRatio = std::isfinite(ratio) ? float(ratio) : 1.f;
        const float pitchAmount = std::clamp((safeRatio - 1.05f)/1.15f, 0.f, 1.f);
        // Do not enhance quiet noise/breath or the start-delay silence.
        const float speechAmount = std::clamp((rms - 0.010f)/0.025f, 0.f, 1.f);
        const float target = 0.78f * pitchAmount * speechAmount;
        // Around 25 ms smoothing; no step at fast score-note boundaries.
        mix += 0.001667f*(target - mix);

        const float alignedWet = wetHistory[wetIndex];
        wetHistory[wetIndex] = wet;
        if (++wetIndex == kWetDelay) wetIndex = 0;
        const float out = alignedWet + (mix / std::max(drive, 1.f))*harmonics;
        return std::clamp(out, -1.f, 1.f);
    }
};
} // namespace utamaru
