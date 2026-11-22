# Take viewer — design

2026-11-22. A native place to watch, hear, read, share and delete what a take
produced, instead of handing the MP4 to the gallery and leaving the WAVs,
stems and AI zip scattered over three folders.

Mockups: `.superpowers/brainstorm/49680-1795346087/content/viewer-v3.html`
(not committed; screens numbered 1–8 below refer to it).

## Decisions (from the user)

- **Toolkit: what the app already uses** — framework Views, plain `Activity`,
  no AndroidX, no Material Components, no Compose. Material 3 *look*,
  hand-styled to match the camera's dark theme (black, `#C4C7C5` text,
  `#A8C7FA` accent, `bg_chip` / `bg_panel` shapes).
- **Icons: Material Symbols** (Rounded) as vector drawables, like `ic_settings`
  already is. Checkboxes show a real check mark when ticked (and a dash when
  partly ticked), not just a filled box.
- **Takes screen: a 2-column grid** of thumbnails with a duration badge and
  little icons for which materials the take has (screen 1).
- **Take screen: video + one playhead + tabs** Gestures · Audio · Files
  (screens 3–5), and **fullscreen** in portrait and landscape (screen 8).
- **AI content: the timeline view** of the existing `ai.zip` — no network, no
  AI call, no speech transcription.
- **Gestures: a drawn mini Move** following the playhead — the saved screen,
  pads lighting as hit, knob rings while turning — over a tappable event list
  that seeks the video (screen 3).
- **Audio: a waveform** per source (screen 4).
- **Fullscreen overlay of the Move screen: off by default**, toggled with the
  `auto_awesome` button.
- **Share: a pick sheet** — the take's materials as a checklist, mastered video
  pre-ticked, then the Android share sheet (screen 6).
- **Delete: system trash + Undo** for all of a take's files at once (screen 7).
- **Partial delete** (user: "delete only something … e.g. stems"):
  - inside a take, Files tab select mode — whole materials or single stems (5b);
  - from several takes, grid Delete asks *what*: Everything, or picked
    material types (5c).
- **Extras in v1: multi-select in the grid** and **Free up space** (delete raw
  video, raw WAV and stems, keep the mastered video and the AI timeline).
- Not in v1: rename/favourite, trim/export clip.

## What a take is

A take is its name, `SchwungCam_yyyyMMdd_HHmmss` (`TakeController`), and the
files that carry it. All are in MediaStore:

| material | icon | MediaStore | path |
|---|---|---|---|
| mastered video | `movie` | Video | `Movies/SchwungCam/<name>.mp4` |
| raw video (rescue only) | `videocam` | Video | `Movies/SchwungCam/<name>_raw.mp4` |
| raw audio | `graphic_eq` | Audio | `Music/SchwungCam/<name>_raw.wav` |
| stems | `instant_mix` | Audio | `Music/SchwungCam/<name>/<channel>.wav` |
| AI timeline | `auto_awesome` | Files | `Documents/SchwungCam/<name>.ai.zip` |

`TakeIndex` (pure Kotlin, JVM-tested) groups rows `(uri, displayName,
relativePath, size, dateAdded, duration)` into takes by name. A take with no
mastered video (a rescued one) still shows, with the raw video's thumbnail; a
take with nothing playable (only stems, say) shows a placeholder tile. Names
that don't parse are ignored. Sorted newest first by the name's timestamp.

**Visibility.** Without a storage permission an app sees only the files it
created. After a reinstall those become someone else's and vanish. *Builder's
call:* on first open of the Takes screen, ask for `READ_MEDIA_VIDEO` +
`READ_MEDIA_AUDIO` (API 33+) or `READ_EXTERNAL_STORAGE` (API 29–32, max SDK
32). If refused, work with own files only. The `ai.zip` in `Documents/` is
not media, so after a reinstall it can't be seen even with the permission;
that is accepted.

## Screens

### Takes (screen 1, 2, 5c)

- Opened from the camera's last-take thumbnail, which today launches the
  gallery. New `TakesActivity`; MainActivity's `openTake` starts it instead.
  (Long-press on the thumbnail keeps the old "open in gallery" path — builder's
  call.)
- Top bar: back, title "Takes", total size of all takes.
- Grid: 2 columns, a tile per take, 3:4, centre-cropped thumbnail from
  `ContentResolver.loadThumbnail`. Tile shows time (date grouping below),
  duration badge top right, and a row of material icons at the bottom.
- Day headers ("Today · 3 takes · 8.4 GB", "Yesterday", "Sat 21 Nov") span both
  columns. Framework `GridView` cannot do spanning headers, so: a `ListView`
  whose rows are either a header or a pair of tiles (builder's call).
- Tap → Take screen. Long-press → select mode: tiles shrink with a check
  circle, top bar turns into "N selected · size" with `share` and `delete`.
  Back or `close` leaves it.
- Delete in select mode → bottom sheet "Delete from N takes": **Everything**
  (ticked by default; ticking it ticks and dims the type rows), or untick to
  pick types: Mastered video, Raw video, Raw audio, Stems, AI timeline — each
  row with file count and size across the selection, rows hidden when the
  selection has none of that type. Button "Move to trash". Then the trash flow
  below.
- Share in select mode → the share pick sheet, by type across the selection.
- Overflow ⋮ (top bar): "Free up space…" — the same sheet over **all** takes
  with Raw video + Raw audio + Stems pre-ticked.
- Refresh on resume (files may have changed in the gallery).

### Take (screens 3, 4, 5, 5b)

New `TakeActivity`, given the take name.

- Top bar: back; title = date and time, subtitle = duration · total size;
  `share`, `delete`, `more_vert`.
- Player: `TextureView` + `MediaPlayer` on the mastered video (or the raw
  video). Letterboxed to the video's aspect. A `fullscreen` button bottom right.
- One scrubber (time · bar · duration) and transport row: `replay_10`,
  `play_arrow`/`pause`, `forward_10`. The playhead drives every tab.
- Tabs, a hand-made strip (three `TextView`s with an underline). A tab is shown
  only when the take has its material: Gestures (needs `ai.zip`), Audio (needs
  any audio), Files (always). Last-picked tab remembered in `Prefs`.

**Gestures tab (screen 3)**

- `MoveView`, a custom View that draws a simplified Move: the 128×64 screen at
  1:1 pixels-ish scale (nearest-neighbour), 8 knobs + volume in a row with
  rings, 4×8 pads, 16 steps, and the transport buttons that have LEDs (Play,
  Record, Sample, Loop, Mute).
- State at time *t* comes from `GestureState.at(t)` (pure Kotlin over the
  parsed `timeline.jsonl`):
  - screen = newest `screen` line with `t ≤ now` (the zip only holds screens
    around gestures — the image holds until the next one; that is correct, it's
    what the README describes);
  - pad lit while held (`on` until its `off`); after release it fades over
    300 ms. Brightness by velocity;
  - knob ring lit during a `turn` (`t … t+dur`) and while touched; the ring
    shows the turn direction (delta sign);
  - step flashes 300 ms when pressed (the zip has no step LEDs — the playhead
    walk is not available, so no moving step);
  - buttons flash while held; LEDs Play/Record/… from the last `led` line.
  - `gap` events are marked in the list ("5 events lost").
- Event list under it: one row per event (`pad`, `step`, `turn`, `button`,
  `touch`, `led`, `gap`; `screen` rows hidden) — time `mm:ss.s`, a colour dot
  by kind, text ("Pad r2c5 · vel 96", "Knob 3 +12", "Play"). Auto-scrolls to
  keep the current event in view unless the user scrolled in the last 3 s.
  Tap seeks the video to that event minus 0.5 s.
- Parsing: `ZipInputStream` over the content URI, `timeline.jsonl` read into
  memory (it is small), screen PNGs decoded lazily by `BitmapFactory` and
  cached (LRU, ~32). `org.json` is in the framework.

**Audio tab (screen 4)**

- Source chips: **Mastered** (the MP4's own track), **Raw** (`_raw.wav`),
  **Stems mix** — only those that exist.
- A lane per source available: Master (mastered), Raw, and one per stem, each
  a min/max waveform with the shared playhead line; tap or drag on a lane
  seeks.
- Stem lanes have **S**olo / **M**ute toggles, active only in Stems mix.
- Below: integrated loudness and true peak when known. *Builder's call:* drop
  this line if it isn't cheaply available; don't add a new measurement pass.
- Playing a non-Mastered source: the `MediaPlayer` is muted and an
  `AudioTrack` plays the WAV(s) mixed (24-bit stems / 16-bit raw → float, sum
  with solo/mute gains, soft-clip). The **video is the clock**: on play, seek
  and every second, if audio drifts more than 40 ms from
  `mediaPlayer.currentPosition`, the audio reader re-seeks. All WAVs already
  start at the video's first frame (that is how they were written), so the
  offset is zero.
- Waveforms: `Peaks` (pure Kotlin) → 1 min/max pair per 1/50 s per channel
  summed to mono, stored as bytes, cached in `cacheDir/peaks/<name>/<file>.bin`
  keyed by size + date. WAV read directly; the MP4's AAC decoded with
  `MediaExtractor` + `MediaCodec` once, on a background thread, the lane shows
  "Reading…" until done.

**Files tab (screens 5, 5b)**

- One row per material: icon, label, size, folder; stems as one row "Stems ·
  8 files · 2.1 GB" that expands to a row per stem. Each row has a `share`
  icon. Total at the bottom.
- Long-press a row (or ⋮ → "Select files…") → select mode: checkboxes on every
  row and every stem; the Stems group row is ticked / dash / empty from its
  children. Top bar "N selected · size", `select_all`. Bottom bar: Share,
  Delete (red). Delete uses the trash flow; if the selection is every file of
  the take, the confirmation says it deletes the whole take and then the screen
  closes.
- ⋮ menu: Select files…, Free up space (this take; lists the size it frees;
  hidden if nothing raw), Open in gallery, Delete permanently.

### Fullscreen (screen 8)

- Same `TakeActivity`; the chrome and tabs are hidden, system bars hidden
  (immersive sticky), the video fills letterboxed.
- Entering: the `fullscreen` button, or rotating the phone to landscape while
  a 16:9 take plays (like YouTube). For a 16:9 take the button also requests
  landscape (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`); for a 9:16 take it stays
  portrait. Exiting: `fullscreen_exit`, back, or rotating back to portrait for
  16:9. Orientation released to `fullSensor` when out of fullscreen.
- Controls (back, scrubber, play/pause, `auto_awesome`, `fullscreen_exit`)
  auto-hide after 3 s, tap shows them.
- `auto_awesome` toggles a floating Move screen (128×64 scaled 2–3×, top right,
  semi-opaque), off by default each time; hidden when the take has no
  `ai.zip`.
- `TakeActivity` handles `orientation|screenSize|screenLayout` config changes
  itself so playback never restarts.

## Share (screen 6)

Bottom sheet "Share take" (or "Share N takes"): a checkbox row per material
type present — Video (mastered), Raw video, Raw audio WAV, Stems (N WAVs), AI
timeline (.ai.zip · "for captioning with an AI") — each with its size; mastered
video pre-ticked (or raw video, if no mastered). "Select all". Button "Share
2.92 GB" with the live total, disabled at 0. Then `ACTION_SEND` (one file) or
`ACTION_SEND_MULTIPLE`, the MediaStore content URIs, `FLAG_GRANT_READ_URI_PERMISSION`,
MIME of the file if all equal else `*/*`, via `Intent.createChooser`. No zipping.

Per-file share icons in the Files tab skip the sheet.

## Delete

- **Trash (API 30+)**: `MediaStore.createTrashRequest(resolver, uris, true)` →
  one system consent dialog for all the files → on OK the files are trashed
  (Android purges after 30 days), the UI drops them and shows a snackbar "Moved
  to trash · UNDO" for 6 s. Undo → `createTrashRequest(…, false)`; if that
  shows a second system dialog, it is accepted. *Verify on the phone* whether
  the app's own files trash without the dialog; if the app's own files can be
  trashed by `update(IS_TRASHED=1)` directly, prefer it for own files and use
  the request only for others'.
- **Delete permanently** (⋮ menu) and **Android 10** (API 29, no trash): a
  confirmation dialog listing what goes and its size, then
  `createDeleteRequest` (30+) or `resolver.delete` (29, own files; others'
  throw `RecoverableSecurityException` → its `userAction`).
- The snackbar is a hand-made view (`bg_panel` style), bottom of the screen.
- After a delete the grid/files refresh; the camera's last-take thumbnail
  already falls back to the newest remaining take (`showLastTake`).
- Deleting stems removes `Music/SchwungCam/<name>/*.wav`; the empty folder is
  left (MediaStore can't delete folders; harmless).

## Code

New package `io.github.dimadake.schwungcam.viewer`. Pure Kotlin, JVM-tested:

- `TakeIndex` — rows → `Take(name, time, materials)`, `Material(kind, files)`,
  sizes, durations, day groups, "free up space" picks.
- `Timeline` parsing → `GestureEvent`s (a sealed class), and `GestureState.at(t)`.
- `Peaks` — PCM (16/24-bit, float) → min/max buckets; byte (de)serialisation.
- `StemMix` — gains from solo/mute, summing, soft-clip.

Android only at the edges:

- `MediaQuery` — the three MediaStore queries → `TakeIndex` rows.
- `TakesActivity`, `TakeActivity`, `MoveView`, `WaveLane`, `CheckBoxView`
  (Material check), `Sheet` (a bottom-sheet `Dialog`), `Snackbar`, `Trash`
  (request + result routing via `startIntentSenderForResult`), `Sharer`,
  `MixPlayer` (AudioTrack thread), `AacPeaks`.
- `res/drawable/ic_*.xml` from Material Symbols Rounded (Apache-2.0; add a
  `THIRD_PARTY_LICENSES.md` row).

Tests: grouping (all kinds, rescued take, stems folder, junk names, sizes);
day grouping; free-up-space picks; timeline parsing of every line type from the
AI spec; `GestureState` (pad held/fade, turn window, screen lookup before the
first screen and between screens, LEDs); peaks of a known sine; stem mix with
solo/mute.

## Not in scope

Rename, favourites, trimming/export, re-mastering, speech transcription, AI
captioning, editing the timeline, cloud upload.
