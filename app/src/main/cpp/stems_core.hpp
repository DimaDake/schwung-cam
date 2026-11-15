// SPDX-License-Identifier: GPL-2.0-or-later
//
// The Link Audio half of the stems feature, free of JNI so the Mac CLI
// (tools/stems_probe.cpp) runs the same code against a real Move.
#pragma once

#include <ableton/LinkAudio.hpp>

#include <cstdint>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace stems {

/** One received buffer, stereo int16 whatever the sender's channel count. */
struct Block {
    uint64_t count = 0;     // the sender's sequence number
    uint32_t rate = 0;
    int64_t startUs = -1;   // Link clock time its first sample leaves the sender; -1 = other session
    std::vector<int16_t> lr;
};

struct ChannelInfo {
    std::string id;         // hex, stable for the channel's lifetime
    std::string peer;
    std::string name;
};

std::string hexId(const ableton::link_audio::Id &id);

/** Per-channel queue cap (~1.5 s at 128-frame buffers): a stalled drainer costs memory, not a crash. */
constexpr size_t QUEUE_MAX = 512;

/** The search cycle while alone, in ms: in the session for LOOK of every CYCLE. A Mac on WiFi saw its first peer after 50-530 ms. */
constexpr int64_t LOOK_MS = 1500, CYCLE_MS = 4000;

/** Not thread-safe: one caller thread (the app's stems thread, or the probe's main). */
class Core {
public:
    Core();
    ~Core();

    /**
     * Keeps the peer in a session only while another peer is in it: Link keeps
     * the OLDER of two sessions, so a camera sitting alone would hand its
     * tempo to a movy that switches LINK on later. Call every ~100 ms.
     */
    void poll(int64_t nowMs);
    void setActive(bool on, int64_t nowMs);

    std::vector<ChannelInfo> channels() const;
    /** Subscribes to exactly [ids] (those present now); returns the ids subscribed. */
    std::vector<std::string> subscribe(const std::vector<std::string> &ids);
    /** Takes up to [max] of what [id] has received, oldest first. */
    std::vector<Block> drain(const std::string &id, size_t max = QUEUE_MAX);
    int64_t nowUs() const;
    size_t peers() const;

private:
    struct Source {
        std::unique_ptr<ableton::LinkAudioSource> src;
        std::mutex mu;
        std::deque<Block> q;
    };

    void onBuffer(Source &s, const ableton::LinkAudioSource::BufferHandle &h);
    void join(bool on);

    ableton::LinkAudio link_;
    std::unique_ptr<ableton::LinkAudioSink> ack_;
    mutable std::mutex mu_;
    std::map<std::string, std::shared_ptr<Source>> sources_;
    bool active_ = false, joined_ = false;
    int64_t phaseMs_ = 0;
};


}  // namespace stems
