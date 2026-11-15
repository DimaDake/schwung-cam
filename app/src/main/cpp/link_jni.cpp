// SPDX-License-Identifier: GPL-2.0-or-later
//
// JNI for io.github.dimadake.schwungcam.link.LinkNative. Every call comes from
// the app's one stems thread (stems::Core is single-caller).
#include "stems_core.hpp"

#include <jni.h>

#include <algorithm>
#include <map>
#include <string>
#include <vector>

namespace {

struct Handle {
    stems::Core core;
    // a block that did not fit the caller's arrays waits for the next drain
    std::map<std::string, stems::Block> carry;
};

Handle *H(jlong h) { return reinterpret_cast<Handle *>(h); }

std::string str(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

jobjectArray strings(JNIEnv *env, const std::vector<std::string> &v) {
    jobjectArray a = env->NewObjectArray((jsize)v.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < v.size(); i++) {
        jstring s = env->NewStringUTF(v[i].c_str());
        env->SetObjectArrayElement(a, (jsize)i, s);
        env->DeleteLocalRef(s);
    }
    return a;
}

}  // namespace

#define FN(name) Java_io_github_dimadake_schwungcam_link_LinkNative_##name

extern "C" {

// A C++ exception crossing JNI aborts the app: the SDK's network setup can
// throw, so the calls that reach it answer "no Link" instead.
JNIEXPORT jlong JNICALL FN(create)(JNIEnv *, jobject) {
    try { return reinterpret_cast<jlong>(new Handle()); } catch (...) { return 0; }
}

JNIEXPORT void JNICALL FN(destroy)(JNIEnv *, jobject, jlong h) { delete H(h); }

JNIEXPORT void JNICALL FN(setActive)(JNIEnv *, jobject, jlong h, jboolean on, jlong nowMs) {
    try { H(h)->core.setActive(on, nowMs); } catch (...) {}
    if (!on) H(h)->carry.clear();
}

JNIEXPORT void JNICALL FN(poll)(JNIEnv *, jobject, jlong h, jlong nowMs) {
    try { H(h)->core.poll(nowMs); } catch (...) {}
}

JNIEXPORT jint JNICALL FN(peers)(JNIEnv *, jobject, jlong h) { return (jint)H(h)->core.peers(); }

JNIEXPORT jlong JNICALL FN(nowUs)(JNIEnv *, jobject, jlong h) { return H(h)->core.nowUs(); }

/** "id\tpeer\tname" per channel. */
JNIEXPORT jobjectArray JNICALL FN(channels)(JNIEnv *env, jobject, jlong h) {
    std::vector<std::string> v;
    for (const auto &c : H(h)->core.channels()) v.push_back(c.id + "\t" + c.peer + "\t" + c.name);
    return strings(env, v);
}

JNIEXPORT jobjectArray JNICALL FN(subscribe)(JNIEnv *env, jobject, jlong h, jobjectArray ids) {
    std::vector<std::string> v;
    for (jsize i = 0; i < env->GetArrayLength(ids); i++) {
        auto s = (jstring)env->GetObjectArrayElement(ids, i);
        v.push_back(str(env, s));
        env->DeleteLocalRef(s);
    }
    std::vector<std::string> got;
    try { got = H(h)->core.subscribe(v); } catch (...) {}
    for (auto it = H(h)->carry.begin(); it != H(h)->carry.end();) {
        if (std::find(got.begin(), got.end(), it->first) == got.end()) it = H(h)->carry.erase(it);
        else ++it;
    }
    return strings(env, got);
}

/**
 * Fills [meta] with (count, rate, startUs, frames) per block and [pcm] with
 * their stereo frames back to back; returns the number of blocks.
 */
JNIEXPORT jint JNICALL FN(drain)(JNIEnv *env, jobject, jlong h, jstring jid, jlongArray meta, jshortArray pcm) {
    const std::string id = str(env, jid);
    const jsize maxBlocks = env->GetArrayLength(meta) / 4, cap = env->GetArrayLength(pcm);
    jsize blocks = 0, used = 0;
    auto &carry = H(h)->carry;
    while (blocks < maxBlocks) {
        stems::Block b;
        auto c = carry.find(id);
        if (c != carry.end()) { b = std::move(c->second); carry.erase(c); }
        else {
            auto got = H(h)->core.drain(id, 1);
            if (got.empty()) break;
            b = std::move(got[0]);
        }
        const auto n = (jsize)b.lr.size();
        if (used + n > cap) { carry[id] = std::move(b); break; }
        const jlong m[4] = {(jlong)b.count, (jlong)b.rate, (jlong)b.startUs, (jlong)(n / 2)};
        env->SetLongArrayRegion(meta, blocks * 4, 4, m);
        env->SetShortArrayRegion(pcm, used, n, b.lr.data());
        used += n;
        blocks++;
    }
    return blocks;
}

}  // extern "C"
