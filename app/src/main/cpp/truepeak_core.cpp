// SPDX-License-Identifier: GPL-2.0-or-later
#include "truepeak_core.hpp"

#include <cmath>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace scdsp {

StereoTruePeak::StereoTruePeak(const double* coef, double maxAbsSum) : maxAbsSum_(maxAbsSum) {
    for (int p = 0; p < kPhases; p++)
        for (int t = 0; t < kTaps; t++) coefT_[t][p] = coef[p * kTaps + t];
}

void StereoTruePeak::process(const double* l, const double* r, int n, double floor, double* out) {
    for (int i = 0; i < n; i++) {
        hl_[idx_] = l[i]; hl_[idx_ + kTaps] = l[i];
        hr_[idx_] = r[i]; hr_[idx_ + kTaps] = r[i];
        idx_ = idx_ == kTaps - 1 ? 0 : idx_ + 1;
        const double a = one(hl_, l[i], floor);
        const double b = one(hr_, r[i], floor);
        out[i] = a > b ? a : b;
    }
}

double StereoTruePeak::one(double* hist, double x, double floor) const {
    const double* h = hist + idx_;
#if defined(__aarch64__)
    float64x2_t m = vdupq_n_f64(0.0);
    for (int t = 0; t < kTaps; t += 2) m = vmaxq_f64(m, vabsq_f64(vld1q_f64(h + t)));
    const double histMax = vmaxvq_f64(m);
#else
    double histMax = 0.0;
    for (int t = 0; t < kTaps; t++) histMax = std::fmax(histMax, std::fabs(h[t]));
#endif
    // no interpolated point exceeds histMax·Σ|c|: skip the filter when that is safe
    const double bound = histMax * maxAbsSum_;
    if (bound < floor) return bound;
    double peak = std::fabs(x);
#if defined(__aarch64__)
    float64x2_t a0 = vdupq_n_f64(0.0), a1 = a0, a2 = a0, a3 = a0;
    for (int t = 0; t < kTaps; t++) {
        const float64x2_t v = vdupq_n_f64(h[t]);
        const double* c = coefT_[t];
        a0 = vfmaq_f64(a0, vld1q_f64(c), v);
        a1 = vfmaq_f64(a1, vld1q_f64(c + 2), v);
        a2 = vfmaq_f64(a2, vld1q_f64(c + 4), v);
        a3 = vfmaq_f64(a3, vld1q_f64(c + 6), v);
    }
    const float64x2_t mx = vmaxq_f64(vmaxq_f64(vabsq_f64(a0), vabsq_f64(a1)), vmaxq_f64(vabsq_f64(a2), vabsq_f64(a3)));
    peak = std::fmax(peak, vmaxvq_f64(mx));
#else
    for (int p = 0; p < kPhases; p++) {
        double acc = 0.0;
        for (int t = 0; t < kTaps; t++) acc += coefT_[t][p] * h[t];
        peak = std::fmax(peak, std::fabs(acc));
    }
#endif
    return peak;
}

}  // namespace scdsp
