// SPDX-License-Identifier: GPL-2.0-or-later
// JNI for dsp/NativePeaks.kt: one call per block, never per sample.
#include <jni.h>

#include "truepeak_core.hpp"

using scdsp::StereoTruePeak;

extern "C" {

JNIEXPORT jlong JNICALL Java_io_github_dimadake_schwungcam_dsp_NativePeaks_nativeNew(JNIEnv* env, jclass, jdoubleArray coef, jdouble maxAbsSum) {
    jdouble* c = env->GetDoubleArrayElements(coef, nullptr);
    auto* tp = new StereoTruePeak(c, maxAbsSum);
    env->ReleaseDoubleArrayElements(coef, c, JNI_ABORT);
    return reinterpret_cast<jlong>(tp);
}

JNIEXPORT void JNICALL Java_io_github_dimadake_schwungcam_dsp_NativePeaks_nativeProcess(
        JNIEnv* env, jclass, jlong h, jdoubleArray l, jdoubleArray r, jint n, jdouble floor, jdoubleArray out) {
    // critical: no copies of three arrays per block; nothing here calls back into Java
    auto* pl = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(l, nullptr));
    auto* pr = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(r, nullptr));
    auto* po = static_cast<jdouble*>(env->GetPrimitiveArrayCritical(out, nullptr));
    reinterpret_cast<StereoTruePeak*>(h)->process(pl, pr, n, floor, po);
    env->ReleasePrimitiveArrayCritical(out, po, 0);
    env->ReleasePrimitiveArrayCritical(r, pr, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(l, pl, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_io_github_dimadake_schwungcam_dsp_NativePeaks_nativeFree(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<StereoTruePeak*>(h);
}

}
