# Schwung Cam

An Android video recorder for performances on an Ableton Move running
[Schwung](https://github.com/charlesvestal/schwung). It films with any phone
camera and records the Move's audio **straight from Schwung**, not through the
microphone. The audio comes over the same stream `http://move.local:7700/mirror`
uses and is synced to the picture automatically. The Move's screen can be
burned into the video, and the audio is mastered for YouTube after each take.

**How to use it: [the manual](docs/MANUAL.md).** Download the APK from
[Releases](https://github.com/DimaDake/schwung-cam/releases).

## Features

- **Pixel Camera-style UI** in portrait and landscape: zoom chips, a
  shutter that becomes a stop button, a last-take thumbnail that opens the
  video in the gallery, and quick toggles over the viewfinder.
- **Camera**: every lens the phone exposes (main, ultrawide, tele, front);
  720p / 1080p / 4K at a fixed 30 fps, as 16:9 or, held upright, 9:16;
  **exposure lock**, which locks AE and white balance together.
- **Schwung audio**: 44.1 kHz stereo from `/stream-auto?v=2&audio=1`. Dropouts
  are filled with silence so the timeline never shrinks.
- **Sync, automatic**: pcm arrivals are lower-enveloped per second, so Wi-Fi
  jitter drops out, then fitted as a line, so Move↔phone clock drift drops out.
  Video PTS are mapped onto the Move's sample clock, so audio is never
  resampled.
- **Screen overlay**: the 128×64 Move screen, drag to move and pinch to scale.
  Black or white pixels can be made transparent, with an opacity slider. The
  same geometry is used for the preview and the file.
- **Mastering** (after stop): 20 Hz high-pass → glue compressor (2:1) →
  loudness normalisation (BS.1770-4, **−14 LUFS** for YouTube, or −9) →
  20 kHz low-pass → 8× true-peak limiter at **−1 dBTP**. Output is AAC-LC
  384 kbps (or the encoder's max), encoded by up to four encoders side by
  side on cuts they agree on to the bit, and H.264 High, in
  `Movies/SchwungCam/*.mp4`. You can also keep the raw WAV
  (`Music/SchwungCam`).
- **AI timeline** (Settings, off by default): beside each take,
  `Documents/SchwungCam/<take>.ai.zip` holds what the hands did on the Move
  (pads, steps, knob turns, buttons, transport lights) on the video's clock,
  and the screens around each gesture as 1-bit PNGs plus contact sheets, with
  a README for the agent. Hand it to an AI to caption the video. Screens are
  picked around input, not on change, so an animated Movy page costs a few
  frames per gesture. Design: `docs/superpowers/specs/2026-10-26-ai-timeline-design.md`.

- **Link Audio stems** (Settings, off by default): pick any channels the Link
  session shares (movy's 16 tracks, 3 sends and master, or Live's) and each
  take also saves them raw as 24-bit WAVs in `Music/SchwungCam/<take>/`, every
  file starting at the video's first frame, so they drop into a DAW at 0 lined
  up with the picture. Buffers are placed by the Link beat they were stamped
  with, so Wi-Fi delay does not move them. Turn on LINK in movy's Settings.
  Design: `docs/superpowers/specs/2026-11-15-link-stems-design.md`.

- **Takes** (the last-take thumbnail; long-press opens it in the gallery):
  every take two to a row by day, with icons for what it has. A take plays
  on one playhead with a drawn Move beside it (its screen, pads, knobs,
  steps and lights from the AI timeline, plus the gesture list), waveforms
  of the master, the raw WAV and each stem, which can play instead (stems
  with solo/mute), and its files. Share any of them; trash a whole take,
  some files, or by kind across takes (with Undo); "Free up space" drops
  the raw files and stems. Fullscreen either way round. Design:
  `docs/superpowers/specs/2026-11-22-take-viewer-design.md`.

## On the Move

Audio flows only while Schwung's **Mirror Display** setting is on. The status
line says "screen only" when it is off. The phone and the Move must be on the
same network; `move.local` is resolved by the system or by a built-in mDNS
query, or you can enter an IP in Settings.

## Build and install

Needs JDK 17, the Android SDK (platform 34), NDK 27.2.12479018 and CMake 3.22.1
(`sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"`), and the Link submodule
(`git submodule update --init --recursive`).

```bash
./gradlew testDebugUnitTest    # JVM tests: parser, sync, take, loudness, limiter, mastering
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
tools/build-probe.sh           # the stems core on this Mac: build-probe/stems_probe --channel "Movy Master";
                               # also the native true-peak library, which NativePeaksTest then checks against Kotlin
```

Install the release build for recording: a debuggable build runs the
mastering DSP many times slower on ART, so a 20-minute take spends many
minutes on "Saving". (`assembleDebug` is for the debugger; it also lets
`adb shell run-as` reach a take's raw files in the app cache.)

## License

GPL-2.0-or-later (`LICENSE.md`), because it links the Ableton Link SDK; see
`THIRD_PARTY_LICENSES.md`.

Design: [`docs/superpowers/specs/2026-10-24-schwung-cam-design.md`](docs/superpowers/specs/2026-10-24-schwung-cam-design.md).
