# Schwung Cam — design

Android video recorder for performances on an Ableton Move running Schwung.
It films with the phone camera, records the Move's audio straight from Schwung
(no microphone), optionally burns the Move's screen into the picture, and
masters the audio for a YouTube upload.

## Goals

- Record video from any phone camera (main, ultrawide, tele, front), with an
  exposure (and white-balance) lock.
- Record Schwung's audio from the same stream `http://move.local:7700/mirror`
  uses, and keep it in sync with the picture with no manual trim.
- Optionally burn the Move screen (128×64) into the video, positioned and
  scaled by touch, with black or white keyed out.
- Master the audio after the take (−14 LUFS / −1 dBTP by default) and write
  a YouTube-ready MP4 to `Movies/SchwungCam`.

Non-goals: microphone recording, live streaming, editing.

## Source stream

`GET http://<host>:7700/stream-auto?v=2&audio=1` (schwung-manager proxies
display-server's `:7681`). Server-Sent Events, about one tick every 33 ms.
Within a tick the order is: screen, then `surface`/`e16`, then `pcm`.

- Unnamed event: `{"format":"mono1_packed"|"gray4_packed",
  "width":128,"height":64,"data":<b64>}`. `mono1`: 8 pages × 128 columns, one
  byte per column per page, bit *b* is row `page*8+b`. `gray4`: row-major,
  two pixels per byte, high nibble first. Sent only when the frame changes.
- `event: pcm`: `{"pos":<device frame index of the first sample>,"n":<frames>,
  "rate":44100,"d":<b64 int16 LE stereo interleaved>}`. `pos` is an absolute
  device counter, so a jump forward is a gap.
- `event: hb` every 2 s. No event for 5 s means the connection is dead:
  reconnect with a 1 s backoff.
- Audio only flows while Schwung's "Mirror Display" setting is on. If the
  screen is live but there has been no `pcm` for 2 s, the status shows
  "no audio — enable Mirror Display in Schwung".

Host default `move.local`, editable (an IP works). `.local` is resolved by the
system resolver first, then by our own one-shot mDNS A query (UDP
224.0.0.251:5353, under a `MulticastLock`). A `WIFI_MODE_FULL_LOW_LATENCY`
Wi-Fi lock is held while the app is in the foreground, so Wi-Fi power-save
batching does not delay packets.

## Clocks and sync (automatic)

The phone timeline is the camera's timestamp base
(`SENSOR_INFO_TIMESTAMP_SOURCE`: REALTIME → `elapsedRealtimeNanos`, else
`System.nanoTime`). Every stream event is stamped with that clock as soon as
its terminating blank line is parsed.

**Envelope.** For each `pcm` chunk, `d = arrival − (pos+n)/rate`; `pos+n` is
the newest sample display-server had when it sent the chunk. Within each
1-second bucket we keep the minimum `d`. Network jitter only ever adds delay,
so the bucket minima trace the true clock offset plus the minimum transit
(a few ms).

- **Live map** (for the overlay): `offset = min(bucket minima of the last 10 s)`;
  `phoneTime(pos) = pos/rate + offset`.
- **Final fit** (for the file): least squares over the take's bucket minima as
  a line `phone = a + b·posSec`. Points more than 2 ms above the line are
  dropped and the line refitted. With fewer than 5 buckets, `b = 1` and
  `a = min`. This absorbs crystal drift between the Move and the phone.

**The file's timeline is the Move's sample clock.** Audio samples are never
resampled. Video PTS are mapped onto the audio clock:
`pts(frame) = (fitPos(sensorTime) − P0)/rate`, where `P0 = fitPos(first frame)`.
Drift (tens of ppm) moves frame times invisibly instead of stretching audio.

**Screen frames** are stamped through the `pcm` chunk that follows them in the
same tick: `stamp = phoneTime(pos+n of that chunk)` under the live map. If no
`pcm` arrives within 20 ms (audio off), `stamp = arrival`. The compositor
keeps the last 32 screen frames and, for a camera frame exposed at `T`, uses
the newest screen frame with `stamp ≤ T`.

## Camera

Camera2. The picker lists each camera id plus the physical lenses of logical
multi-cameras (`physicalCameraIds`, via `OutputConfiguration.setPhysicalCameraId`).
Each entry is labelled by facing and 35 mm-equivalent focal length
("Back 24mm", "Back 13mm", "Front").

- Fixed `CONTROL_AE_TARGET_FPS_RANGE` [30,30], so low light never drops the
  frame rate.
- **Exposure lock** sets `CONTROL_AE_LOCK` and `CONTROL_AWB_LOCK` together.
  It starts off at every launch (a lock from yesterday is the wrong exposure
  for today's light) and survives only a rotation.
- Output 720p / 1080p (default) / 2160p, whichever are 16:9 and supported. The
  camera stream is center-cropped to 16:9 in the shader.
- The activity follows the phone in every orientation (as camera apps do,
  regardless of the system rotation lock) and locks the current one from
  Record until the take is saved. Portrait records a 9:16 file (the chosen
  resolution stood on end); an encoder that cannot take that size, e.g.
  portrait 4K on some phones, is refused at Record with a message. Camera2 already gives the stream a transform for the
  sensor orientation, and the `SurfaceTexture` matrix includes it, so the
  image is upright in the phone's natural orientation and only the display
  rotation is applied on top (`CameraUv`). *Changed in implementation:*
  rotating by the sensor orientation as well turned the video 90° on a
  Pixel 9. Front lenses are never mirrored: the compositor undoes the stream's
  mirror, so preview and file agree. Not `MIRROR_MODE_NONE`: on a Pixel 9 it
  turns the picture upside down.

## Compositor (OpenGL ES 2)

One render thread, one EGL context. The camera feeds a `SurfaceTexture`
(OES). On each camera frame:

1. Draw the camera quad (crop + display rotation + `SurfaceTexture` matrix).
2. Draw the screen quad if the overlay is on: a 128×64 luminance texture
   sampled `GL_NEAREST`.
3. Render into the preview surface (letterboxed 16:9) and, while recording,
   into the encoder input surface with `eglPresentationTimeANDROID(sensorTime)`.

**Overlay model** (persisted): `enabled`, `cx, cy` (center, 0..1 of the
frame), `w` (width fraction 0.05..1; the height follows the 2:1 aspect),
`key` ∈ {none, black transparent, white transparent}, and `opacity` 0..1.
Alpha per pixel is: `1` for none, `lum` for black transparent, `1−lum` for
white transparent; each is multiplied by `opacity`. Drag moves the center and
pinch scales; the overlay is clamped to stay inside the frame. Place and size
are kept separately for portrait and landscape. The geometry
lives in normalised frame coordinates, so the preview is exactly the file.

## Recording

- **Video**: MediaCodec H.264 High, 30 fps, I-frame every 1 s, 8 / 16 / 45 Mbps
  for 720p / 1080p / 2160p (YouTube upload recommendations). It is written to
  a temporary video-only MP4 in the app cache. The raw sensor time of the first
  frame is saved alongside.
- **Audio**: a 2 s pre-roll ring of `pcm` is always kept. When recording
  starts, the WAV writer starts at the oldest pre-roll sample and appends
  chunks in `pos` order, filling gaps with silence. A `pos` that goes
  backwards (the device restarted) ends audio capture for the take, with a
  warning. On stop, audio keeps recording until the stream has covered the
  last video frame plus 300 ms, or for 1.5 s at most.
- The WAV is 16-bit stereo 44.1 kHz, with its first `pos` recorded in a
  sidecar (`take.json`, together with the bucket minima).

## Finalize (after stop, with a progress dialog)

1. Fit the clock line; compute `P0` and the end.
2. **Master** the WAV range [P0, end) (or pass it through when mastering is off):
   1. DC block plus a 20 Hz high-pass (2nd-order Butterworth).
   2. Glue compressor (toggle, on by default): stereo-linked RMS detector
      (10 ms), 2:1, 6 dB soft knee, threshold = pre-master integrated
      loudness + 4 dB, attack 30 ms, release 250 ms.
   3. Gain to target: YouTube −14 LUFS (default) or Loud −9 LUFS. Integrated
      loudness per ITU-R BS.1770-4 (K-weighting, 400 ms blocks with 75%
      overlap, −70 LUFS absolute gate, −10 LU relative gate).
   4. 20 kHz low-pass (4th-order Butterworth), then a true-peak limiter with
      a −1 dBTP ceiling: 8× polyphase peak detection (32 taps per phase,
      Kaiser β=8), 5 ms lookahead, release 80 ms, aiming 0.25 dB under the
      ceiling. *Changed in implementation:* a BS.1770-length 4× detector
      read full-band noise peaks about 3 dB low against a near-ideal
      interpolator, and the limiter overshot −1 dBTP. The longer detector is
      flat to about 20 kHz, hence the low-pass, and the margin covers what is
      left (measured ≈0.15 dB). A cheap exact bound skips the interpolation
      whenever the recent samples cannot reach the ceiling.
   5. Gain is capped at +24 dB, so a near-silent take does not have its
      noise floor raised to the target.
   6. Measure the result. If it is more than 0.5 LU under target (limiter
      loss), raise the gain by the difference and repeat (at most 3 passes).
      Every pass streams from the WAV, so memory stays flat for long takes.
3. Encode AAC-LC stereo 44.1 kHz at 384 kbps (clamped to the encoder's
   supported range) to a temporary `.m4a`.
4. Remux: copy the video samples unchanged, with PTS remapped onto the audio
   clock, and interleave the AAC by time into the final MP4, written through
   MediaStore to `Movies/SchwungCam/SchwungCam_YYYYMMDD_HHMMSS.mp4`.
5. If no `pcm` arrived at all, the MP4 has no audio track and a warning is
   shown. "Keep raw WAV" keeps the unmastered WAV next to it; otherwise all
   temporary files are deleted.

After a take, a thumbnail of it appears next to Record, as in a phone's
own camera app, and opens it in the gallery app (where Share is). On start the
thumbnail shows the newest take in `Movies/SchwungCam`. A short toast confirms
the save; only a warning gets a dialog.

## UI

One activity, laid out like Pixel Camera, with a portrait and a landscape
layout:

- Full-bleed viewfinder (letterboxed to the output aspect). Over its top: a
  status chip (coloured dot: live / no audio / connecting / offline; tap opens
  settings) and round quick toggles for Exposure lock, Move screen and
  Settings (yellow when on).
- Zoom chips (0.5 · 1× · 2×, from 35 mm-equivalent focal lengths relative to
  the main lens) float at the bottom of the viewfinder, or its right edge in
  landscape. A logical camera's own main lens, which appears twice, is listed
  once. The default is the main back lens.
- Controls bar (bottom in portrait, right in landscape): last-take thumbnail,
  the shutter (white ring, red disc → stop square while recording, progress
  ring while saving), and the front/back flip.
- A red timer pill while recording, then "Saving n%".
- Lens, flip, gallery and settings are hidden during a take; Exposure lock and
  Move screen stay live.
- Settings is an in-viewfinder card with chips and switches, applied at once:
  key mode and opacity, resolution, loudness target, mastering, glue, keep
  WAV, host. Tapping the picture closes it.
- Drag and pinch on the picture move and size the Move screen while it is on.

Plain Android Views, Kotlin, minSdk 29, targetSdk 34, no third-party runtime
dependencies. Cleartext HTTP is allowed (the Move speaks plain http).

## Units and tests

| Unit | Responsibility | JVM tests |
|---|---|---|
| `SseParser` | bytes → (event name, data, arrival) | multiline data, named/unnamed, split reads |
| `ScreenDecoder` | mono1/gray4 → 128×64 luminance | known bit patterns |
| `MdnsQuery` | build/parse an A query | packet round trip, compressed names |
| `ClockEnvelope` | bucket minima, live map, final fit | jitter, drift 100 ppm, too-few-points |
| `ScreenStamper` | stamps screen frames via the next pcm | ordering, timeout |
| `PcmTake` | pre-roll + gap fill + backwards stop | gaps, reorder, pre-roll |
| `Timeline` | video PTS → audio clock, trim range | drift mapping |
| `OverlayGeometry` | drag/pinch/clamp → quad | clamping, aspect |
| `CameraUv` | crop + display rotation before the ST matrix | full 16:9 buffer in both landscapes, half turn apart, unmirror |
| `LoudnessMeter` | BS.1770-4 integrated LUFS | EBU Tech 3341 cases 1, 2, 5-ish (gating) |
| `TruePeak` / `Limiter` | 8× TP, lookahead limiting | no peak > −1 dBTP under a 16× reference interpolator |
| `Mastering` | chain + iteration | synthetic take → −14 ±0.5 LUFS, ≤ −1 dBTP; 60 s in < 15 s |
| `MoveStream` | socket → parser → envelope/take/stamper | fake display-server over loopback, with one dropped connection |

Android-only parts (Camera2, GL, MediaCodec, MediaStore) are verified by
building and running on a phone. On the phone, ffprobe of a finished clip
shows both streams starting at 0 and a clap aligned within one frame.
