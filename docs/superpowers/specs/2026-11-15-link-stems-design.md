# Link Audio stems — design

**Ask (owner, 2026-11-15):** "Can we update our schwung camera app so it captures
link audio tracks? By default it is off, but I can enable it and select which
tracks to capture. It should be put as separate wav files near the video."

## Owner's decisions

- **GPL.** The Ableton Link SDK is GPL-2.0-or-later; it is built into the app
  through the NDK, so the app is GPL-2.0-or-later from this change on.
- **Where:** `Music/SchwungCam/<take>/<channel>.wav`, one folder per take, named
  like the video (Android takes no WAV under `Movies/`).
- **Picker:** the channels the Link session announces now (movy's 20 and anything
  Live shares), as switches; picks are remembered by peer + name. A pick that is
  absent during a take is skipped, with a note in the result.
- **Format:** raw (never mastered), the source's sample rate, 24-bit stereo,
  every file starting at the video's first frame, so all of them dropped at 0 in
  a DAW line up with the video.

## How it works

- **Native** (`app/src/main/cpp`, GPL): one `ableton::LinkAudio` peer named
  "Schwung Cam". `stems_core` is plain C++ (no JNI), so a Mac CLI
  (`tools/stems_probe.cpp`) runs the same code against a real Move. It lists
  channels, holds one `LinkAudioSource` per subscribed channel, and queues each
  buffer with the Link-clock time of its first sample:
  `timeAtBeat(info.beginBeats(state, 4), 4)`. The sender stamps a buffer with the
  beat at which it LEAVES ITS SPEAKER (movy: `OUTPUT_LATENCY_US` after render), so
  network delay drops out — the same rule Live records by.
- **Clocks:** Link's clock is `CLOCK_MONOTONIC_RAW`; the camera's is BOOTTIME or
  MONOTONIC. Every drain reads both back to back and converts with that fresh
  offset, so NTP slew between them cannot accumulate over a long take.
- **Placement** (`StemLane`, pure Kotlin): buffers are laid end to end in sample
  order — the sender's sample clock is the timeline, as the Mirror audio's is.
  A buffer whose stamp disagrees with its place by more than 20 ms (a lost run, a
  restart) re-anchors: silence for a hole, an overlap dropped. The file's anchor
  is the median of every buffer's stamp minus its place, so one jittered stamp
  cannot move a stem.
- **Onto the video:** an anchor in camera time goes through the take's own
  `Timeline` (the Mirror audio's clock fit) exactly as video frames and the AI
  timeline do; with no Mirror audio, camera time since the first frame.
- **Pre-roll:** subscribed channels keep 2 s of buffers while the viewfinder is
  open, so a stem has audio from the first frame although its buffers arrive a
  network delay late.
- **Joining:** Link keeps the OLDER session when two meet. A camera that sat
  alone in a session of its own would therefore impose its tempo on a movy that
  turns LINK on after it. So the camera only stays in a session while another
  peer is in it: alone, it leaves and looks again for 1.5 s every 4 s (a Mac on WiFi found its first peer in 50-530 ms). It never
  sets tempo or transport.
- **Cost on the Move:** each subscribed channel is ~1.5 % of a core in
  movy-link (20 channels measured at 30 %); only picked channels are subscribed,
  and only while the setting is on and the viewfinder is open.

## Not in scope

Mastering stems, choosing bit depth, stems for a take with the setting off.
