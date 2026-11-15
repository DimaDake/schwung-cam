// SPDX-License-Identifier: GPL-2.0-or-later
//
// stems_probe: the app's stems_core on a Mac, against a real Link session.
// Subscribes to one channel and reports what StemLane will be handed: buffer
// continuity, how far each stamp sits from its place in sample order (the
// jitter the median anchor must absorb), and how late buffers arrive.
//
//   stems_probe [--channel "Movy Master"] [--secs 10]
//
// Build: tools/build-probe.sh
#include "../app/src/main/cpp/stems_core.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

using namespace std::chrono;

static int64_t ms() { return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count(); }

int main(int argc, char **argv) {
    std::string want = "Movy Master";
    double secs = 10;
    for (int i = 1; i < argc; i++) {
        if (!std::strcmp(argv[i], "--channel") && i + 1 < argc) want = argv[++i];
        else if (!std::strcmp(argv[i], "--secs") && i + 1 < argc) secs = std::atof(argv[++i]);
        else { std::fprintf(stderr, "stems_probe: unknown argument %s\n", argv[i]); return 2; }
    }
    stems::Core core;
    core.setActive(true, ms());
    std::string id;
    const int64_t t0 = ms(), until = t0 + 8000;
    int64_t peersAt = -1;
    while (id.empty() && ms() < until) {
        core.poll(ms());
        if (peersAt < 0 && core.peers() > 0) peersAt = ms() - t0;
        for (const auto &c : core.channels()) if (c.name == want) id = c.id;
        std::this_thread::sleep_for(milliseconds(50));
    }
    std::printf("peers %zu (first after %lld ms, channel after %lld ms)\n", core.peers(), (long long)peersAt, (long long)(ms() - t0));
    for (const auto &c : core.channels()) std::printf("channel %s | %s\n", c.peer.c_str(), c.name.c_str());
    if (id.empty()) { std::printf("no-channel %s\n", want.c_str()); return 1; }
    core.subscribe({id});

    std::vector<double> resid, late;
    long blocks = 0, gaps = 0, other = 0, frames = 0;
    uint64_t lastCount = 0;
    int64_t anchorUs = -1;
    uint32_t rate = 0;
    const int64_t end = ms() + (int64_t)(secs * 1000);
    while (ms() < end) {
        core.poll(ms());
        const int64_t now = core.nowUs();
        for (auto &b : core.drain(id)) {
            if (blocks && b.count != lastCount + 1) gaps++;
            lastCount = b.count;
            blocks++;
            if (b.startUs < 0) { other++; continue; }
            rate = b.rate;
            if (anchorUs < 0) anchorUs = b.startUs;
            resid.push_back((b.startUs - anchorUs) / 1000.0 - frames * 1000.0 / b.rate);
            late.push_back((now - b.startUs) / 1000.0);
            frames += (long)(b.lr.size() / 2);
        }
        std::this_thread::sleep_for(milliseconds(20));
    }
    if (resid.empty()) { std::printf("RED: no buffers\n"); return 1; }
    // residuals relative to their median: what one stamp's jitter is worth
    auto sorted = resid;
    std::sort(sorted.begin(), sorted.end());
    const double med = sorted[sorted.size() / 2];
    std::vector<double> dev;
    for (double r : resid) dev.push_back(std::abs(r - med));
    std::sort(dev.begin(), dev.end());
    std::sort(late.begin(), late.end());
    auto pct = [](const std::vector<double> &v, double p) { return v[std::min(v.size() - 1, (size_t)(p * v.size()))]; };
    std::printf("blocks %ld gaps %ld other_session %ld rate %u frames %ld\n", blocks, gaps, other, rate, frames);
    std::printf("stamp_vs_place_ms p50 %.3f p99 %.3f max %.3f drift_end %.3f\n",
                pct(dev, 0.5), pct(dev, 0.99), dev.back(), resid.back() - resid.front());
    std::printf("arrival_late_ms p50 %.1f p99 %.1f\n", pct(late, 0.5), pct(late, 0.99));
    return 0;
}
