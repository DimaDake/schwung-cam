// SPDX-License-Identifier: GPL-2.0-or-later
#include "stems_core.hpp"

#include <algorithm>
#include <cstdio>

namespace stems {

std::string hexId(const ableton::link_audio::Id &id) {
    std::string s;
    char b[3];
    for (auto v : id) { std::snprintf(b, sizeof b, "%02x", v); s += b; }
    return s;
}

Core::Core() : link_(120.0, "Schwung Cam") {}

Core::~Core() {
    {
        std::lock_guard<std::mutex> g(mu_);
        sources_.clear();
    }
    ack_.reset();
    link_.enable(false);
}

void Core::join(bool on) {
    if (on == joined_) return;
    joined_ = on;
    if (on) {
        link_.enable(true);
        link_.enableLinkAudio(true);
        // A peer must announce a channel of its own before others answer its
        // channel requests (schwung's link_subscriber "Schwung-Ack").
        if (!ack_) ack_ = std::make_unique<ableton::LinkAudioSink>(link_, "Schwung Cam", 256);
    } else {
        {
            std::lock_guard<std::mutex> g(mu_);
            sources_.clear();
        }
        ack_.reset();
        link_.enableLinkAudio(false);
        link_.enable(false);
    }
}

void Core::setActive(bool on, int64_t nowMs) {
    active_ = on;
    phaseMs_ = nowMs;
    poll(nowMs);
}

void Core::poll(int64_t nowMs) {
    if (!active_) { join(false); return; }
    if (joined_ && link_.numPeers() > 0) { phaseMs_ = nowMs; return; }
    join((nowMs - phaseMs_) % CYCLE_MS < LOOK_MS);
}

size_t Core::peers() const { return joined_ ? link_.numPeers() : 0; }

int64_t Core::nowUs() const { return link_.clock().micros().count(); }

std::vector<ChannelInfo> Core::channels() const {
    std::vector<ChannelInfo> out;
    if (!joined_) return out;
    for (const auto &c : link_.channels()) out.push_back({hexId(c.id), c.peerName, c.name});
    return out;
}

void Core::onBuffer(Source &s, const ableton::LinkAudioSource::BufferHandle &h) {
    Block b;
    b.count = h.info.count;
    b.rate = h.info.sampleRate;
    const auto st = link_.captureAppSessionState();
    if (auto beat = h.info.beginBeats(st, 4.0)) b.startUs = st.timeAtBeat(*beat, 4.0).count();
    const size_t n = h.info.numFrames, ch = h.info.numChannels;
    b.lr.resize(n * 2);
    for (size_t f = 0; f < n; f++) {
        b.lr[2 * f] = h.samples[f * ch];
        b.lr[2 * f + 1] = h.samples[f * ch + (ch > 1 ? 1 : 0)];
    }
    std::lock_guard<std::mutex> g(s.mu);
    if (s.q.size() >= QUEUE_MAX) s.q.pop_front();
    s.q.push_back(std::move(b));
}

std::vector<std::string> Core::subscribe(const std::vector<std::string> &ids) {
    std::vector<std::string> got;
    const auto present = joined_ ? link_.channels() : std::vector<ableton::LinkAudio::Channel>{};
    std::lock_guard<std::mutex> g(mu_);
    for (auto it = sources_.begin(); it != sources_.end();) {
        if (std::find(ids.begin(), ids.end(), it->first) == ids.end()) it = sources_.erase(it);
        else ++it;
    }
    for (const auto &c : present) {
        const auto id = hexId(c.id);
        if (std::find(ids.begin(), ids.end(), id) == ids.end()) continue;
        got.push_back(id);
        if (sources_.count(id)) continue;
        auto s = std::make_shared<Source>();
        std::weak_ptr<Source> w = s;
        s->src = std::make_unique<ableton::LinkAudioSource>(link_, c.id,
            [this, w](ableton::LinkAudioSource::BufferHandle h) {
                if (auto p = w.lock()) onBuffer(*p, h);
            });
        sources_[id] = s;
    }
    return got;
}

std::vector<Block> Core::drain(const std::string &id, size_t max) {
    std::shared_ptr<Source> s;
    {
        std::lock_guard<std::mutex> g(mu_);
        auto it = sources_.find(id);
        if (it == sources_.end()) return {};
        s = it->second;
    }
    std::lock_guard<std::mutex> g(s->mu);
    const auto n = std::min(max, s->q.size());
    std::vector<Block> out(std::make_move_iterator(s->q.begin()), std::make_move_iterator(s->q.begin() + n));
    s->q.erase(s->q.begin(), s->q.begin() + n);
    return out;
}

}  // namespace stems
