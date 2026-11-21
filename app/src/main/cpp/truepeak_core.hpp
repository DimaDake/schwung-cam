// SPDX-License-Identifier: GPL-2.0-or-later
// The mastering limiter's true-peak detector (dsp/TruePeak.kt) over a block,
// with NEON on arm64. No JNI here, so tools/ can build and test it on a Mac.
#pragma once

namespace scdsp {

constexpr int kPhases = 8;
constexpr int kTaps = 32;

/** Two TruePeak.kt detectors, left and right, advanced together. */
class StereoTruePeak {
public:
    /**
     * [coef]: kPhases banks of kTaps, each oldest sample first (TruePeak.kt's
     * REV); [maxAbsSum]: its MAX_ABS_SUM, the bound that lets a quiet sample
     * skip the filter.
     */
    StereoTruePeak(const double* coef, double maxAbsSum);

    /** out[i] = the larger of TruePeak.process(l[i], floor) and TruePeak.process(r[i], floor). */
    void process(const double* l, const double* r, int n, double floor, double* out);

private:
    double one(double* hist, double x, double floor) const;

    alignas(16) double coefT_[kTaps][kPhases];  // [tap][phase]: one tap of all phases is two NEON loads
    double maxAbsSum_;
    // each sample is written twice, so the newest kTaps sit contiguous at [idx_, idx_ + kTaps)
    alignas(16) double hl_[2 * kTaps] = {};
    alignas(16) double hr_[2 * kTaps] = {};
    int idx_ = 0;
};

}  // namespace scdsp
