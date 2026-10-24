# Schwung Cam

An Android video recorder for performances on an Ableton Move running
[Schwung](https://github.com/charlesvestal/schwung). It films with any phone
camera and records the Move's audio **straight from Schwung**, not through the
microphone. The audio comes over the same stream `http://move.local:7700/mirror`
uses and is synced to the picture automatically. The Move's screen can be
burned into the video, and the audio is mastered for YouTube after each take.

## Features

- **Camera**: every lens the phone exposes (main, ultrawide, tele, front);
  720p / 1080p / 4K at a fixed 30 fps; **exposure lock**, which locks AE and
  white balance together.
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
  384 kbps (or the encoder's max) and H.264 High, in
  `Movies/SchwungCam/*.mp4`. You can also keep the raw WAV
  (`Music/SchwungCam`).

## On the Move

Audio flows only while Schwung's **Mirror Display** setting is on. The status
line says "screen only" when it is off. The phone and the Move must be on the
same network; `move.local` is resolved by the system or by a built-in mDNS
query, or you can enter an IP in Settings.

## Build and install

Needs JDK 17 and the Android SDK (platform 34).

```bash
./gradlew testDebugUnitTest    # JVM tests: parser, sync, take, loudness, limiter, mastering
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Design: [`docs/superpowers/specs/2026-10-24-schwung-cam-design.md`](docs/superpowers/specs/2026-10-24-schwung-cam-design.md).
