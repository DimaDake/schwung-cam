# AI timeline — design

2026-10-26. A per-take file an AI agent can read to caption the video later,
outside the app: what the hands did on the Move, and what its screen showed.

## Decisions (from the user)

- **Source: the Move's surface only** — pads, steps, knobs, buttons, jog, the
  volume knob, plus transport LEDs. Notes Move's sequencer plays and external
  USB MIDI are NOT in the stream; getting them would need a Schwung change
  (an upstream PR), and is out of scope.
- **Screens as PNGs in a zip**, plus contact sheets, plus a README for the agent.
- **Screens are picked around INPUT, not on change.** Movy and Schwung screens
  animate (knob arcs ease, LFO waves, playheads), so "save when it settles"
  would never fire or fire constantly. Generic for any Schwung module.
- Off by default; a switch in Settings.

## Source: the `surface` event (schwung `35f401c3`)

The app already opens `/stream-auto?v=2&audio=1`, which sends
`event: surface` with `{"v":1,"frame":N,"data":<base64 surface_live_shm_t>}`
(`src/host/surface_live_shm.h`, layout pinned by static asserts, little endian):

| offset | field |
|---|---|
| 16 | `frame` u32 (SPI frames, 128 samples = 2.902 ms each) |
| 20 | `event_count` u32 — ring head |
| 24 / 280 | `note_led[128]` / `cc_led[128]` |
| 1560 / 1688 | `note_down[128]` (velocity) / `note_pressure[128]` |
| 1944 | `enc_pos[10]` i16: knobs 1-8 (CC 71-78), 8 = volume (79), 9 = jog (14) |
| 1976 | `events[32]` of {frame u32, status, d1, d2, pad} at `events[n % 32]` |

- Ring events are note on/off (pads 68-99, steps 16-31, knob touch 0-9) and
  CCs that are not encoders (buttons: 127 down, 0 up). **Encoder turns and
  aftertouch are not events** — only the `enc_pos` counters and
  `note_pressure`, so a turn is the change of `enc_pos` between snapshots, and
  pressure is kept as the peak per held pad.
- An event's time is the snapshot's time minus `(frame − event.frame) × 2.902 ms`.
  The snapshot is dated like a screen frame (`ScreenStamper`: by the next pcm
  chunk of the same tick through the clock envelope), so events and screens sit
  on the clock the video is synced to.
- A jump of `event_count` past 32 lost events: a `gap` event says how many.
- **Measured on the device**: a snapshot is 2232 bytes (= the pinned size),
  magic `SURFLV1`. display-server sends a screen or a surface snapshot only
  WHEN IT CHANGES (an idle Move: 2 screens and 17 snapshots in 3 s), so the
  picker's clock is also advanced by every snapshot, and its `during` / `after`
  frames are chosen by TIME (the newest screen at that moment), never "the
  last one that arrived". A stopped Play LED reads 124 (#141414): Move dims its
  transport lights rather than switching them off, so the palette's blacks and
  dark greys count as off and `led` lines carry the raw `value` too.

## Screen picking

A **gesture** starts with the first input and ends once nothing is held and
there has been no input for 400 ms. Saved frames (identical consecutive ones
dropped):

| why | when |
|---|---|
| `start` | the first screen of the take |
| `before` | the newest screen at least 20 ms older than the gesture's first input |
| `during` | while something is held: a control idle for 300 ms (one knob's turn done, a step held) |
| `release` | the newest screen before the release that empties the held set — the param card is still up |
| `after` | when the gesture ends (400 ms idle) |
| `change` | every 2 s outside a gesture, if > 3 % of pixels (246 of 8192) differ from the last saved — a page Move changed by itself; an animation changes fewer |
| `end` | the last screen at Stop |

## Output: `<take>.ai.zip`

Saved to `Documents/SchwungCam/` with the video's name (MediaStore will not
take a non-media file under `Movies/`).

```
README.md          how to read it, field meanings, Move's layout
timeline.jsonl     one JSON object per line, sorted by t
screens/000042.png 1-bit, 4× (512×256), white on black like the Move
sheets/0003.png    16 screens 4×4 at 2×, each labelled "#042  12.34"
```

Every line has `t` (seconds from the video's first frame, 3 decimals) and
`type`:

- `{"type":"take","name":…,"duration":…}` first line
- `pad` `{"pad":"r2c5","note":84,"on":true,"vel":96}` / `{"on":false,"peak_pressure":40}` — r1 = top row, c1 = left
- `step` `{"step":3,"on":true}`
- `touch` `{"control":"knob3","on":true}` (knob1-8, volume, jog)
- `turn` `{"control":"knob3","delta":12,"dur":0.84}` — one per burst, idle 300 ms splits
- `button` `{"button":"Play","on":true}` (unknown CC → `"cc87"`; jack-detect CCs 114/115 dropped)
- `led` `{"led":"Play","on":true,"value":126}` — Play, Record, Sample, Loop, Mute
- `screen` `{"why":"before","file":"screens/000042.png","sheet":"sheets/0003.png"}`
- `gap` `{"lost":5}`

Step LEDs are left out: the playhead walks them every sixteenth.

## Code

Pure Kotlin under `ai/`, JVM-tested; Android only at the edges.

- `SurfaceSnapshot` — decode the base64 struct.
- `SurfaceEvents` — snapshots → stamped input events (ring diff, `enc_pos` diff, pressure peak, LED diff).
- `ScreenPicker` — the table above, online, fed screens and events.
- `TimelineJson` — events → lines, merging turn bursts.
- `Png1` — 1-bit greyscale PNG writer (Deflater + CRC32), `Font5x7` digits for sheet labels.
- `ContactSheet`, `AiExport` — the zip.
- `AiRecorder` — the take's recorder; `TakeController` starts it, `Finalizer`
  exports it with the take's `Timeline` (video-time mapping).

Tests: snapshot decode at the pinned offsets; ring wrap and loss; event dating;
the picker on a knob gesture over an animated screen (3 frames, not 60), a tap,
a hold with two knob turns, a page change with no input, an animation with no
input (nothing saved); turn merging; PNG round trip through `javax.imageio`; zip
layout.
