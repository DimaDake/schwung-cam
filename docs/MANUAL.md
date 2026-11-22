# Schwung Cam — Manual

Schwung Cam films a performance on an Ableton Move with your phone's camera
and records the Move's own audio over Wi-Fi from
[Schwung](https://github.com/charlesvestal/schwung). The audio does not go
through the microphone. Each take is synced and mastered automatically and
saved as an MP4 that is ready for YouTube.

- [Before you start](#before-you-start)
- [Installing](#installing)
- [The camera screen](#the-camera-screen)
- [Recording a take](#recording-a-take)
- [The Move screen overlay](#the-move-screen-overlay)
- [Settings](#settings)
- [Link Audio stems](#link-audio-stems)
- [AI timeline](#ai-timeline)
- [Takes: watching, sharing, deleting](#takes-watching-sharing-deleting)
- [Where files go](#where-files-go)
- [Troubleshooting](#troubleshooting)

## Before you start

You need:

- An **Ableton Move running Schwung**, with **Mirror Display** turned on in
  Schwung's settings. Schwung sends audio only while Mirror Display is on.
- An **Android 10+ phone** with a 64-bit (arm64) processor, on the **same
  Wi-Fi network** as the Move.
- For stems only: [movy](https://github.com/DimaDake/schwung-movy) with **LINK** turned on in its
  Settings, or any other Link Audio session (such as Ableton Live 12.2+).

## Installing

1. Download `schwung-cam-<version>.apk` from the
   [Releases](https://github.com/DimaDake/schwung-cam/releases) page on the phone.
2. Open it. Android asks you to allow installs from your browser or file
   manager: allow it once.
3. Start **Schwung Cam** and grant **Camera**. The app asks for
   **Notifications** (so a take can keep saving in the background) and, the
   first time you open Takes, for **Photos and videos / Music and audio**
   (so it can still see takes it made before a reinstall).

To update, install the newer APK over the old one. Your takes and settings
are kept.

## The camera screen

```
 ┌─────────────────────────────────────┐
 │ ● Move live              🔒  ▣  ⚙   │  status chip · exposure lock · overlay · settings
 │                                     │
 │            viewfinder               │
 │                    ┌──────┐         │
 │                    │ Move │         │  Move screen overlay (when on)
 │                    └──────┘         │
 │        [0.5]  [1×]  [2]  [5]        │  zoom chips (one per lens)
 │   ▢            ( ● )           ⟲    │  last take · shutter · switch camera
 └─────────────────────────────────────┘
```

The layout works in portrait and landscape. Turn the phone and the controls
follow.

**Status chip** (top left), tap it to open Settings:

| dot | text | meaning |
|---|---|---|
| green | Move live | audio and screen are coming in; ready to record |
| yellow | No audio · turn on Mirror Display | the Move is reachable but sends only the screen |
| grey | Connecting to move.local… | looking for the Move |
| red | Move offline · move.local | not reachable; check the Wi-Fi and the host in Settings |

When stems are on, the chip also shows the Link state, for example
`Link 4/4 stems`.

**Exposure lock** (padlock) locks the exposure and white balance together, so
the picture doesn't pump when the Move's pads light up. Point the camera,
wait for the picture to settle, then tap.

**Overlay** turns the Move screen overlay on and off (see below).

**Zoom chips** switch between the phone's lenses (ultrawide, main, tele). The
chip in use is shown with `×`. **Switch camera** swaps between the front and
back cameras.

**Last take** (the thumbnail at the bottom) opens [Takes](#takes-watching-sharing-deleting).
Long-press it to open the newest video in the gallery instead.

## Recording a take

1. Check that the status chip says **Move live**.
2. Tap the **shutter**. It turns into a stop button and a timer runs. Lens,
   camera, settings and the last-take button are hidden while you record.
3. Play.
4. Tap **stop**. The timer changes to **Saving NN %** while the audio is
   mastered and the MP4 is written. On a Pixel, a 24-minute take takes about
   half a minute.
5. "Saved" appears with the final loudness, and the new take's thumbnail shows
   up bottom left.

Saving carries on if you leave the app, with a notification showing progress.
You can start another take as soon as the shutter comes back.

If you press record before audio is flowing, you get the warning "No Schwung
audio yet — the take may be silent". The take still records, and any gaps in
the audio are filled with silence so the picture and sound stay in sync.

**Sync is automatic.** The app works out the Wi-Fi delay and the clock
difference between the Move and the phone, then places every video frame on
the Move's audio clock. You don't need to clap or line up a slate.

**Portrait or landscape?** Hold the phone the way you want the video to be
when you press record: upright gives 9:16 (Shorts/Reels), sideways gives 16:9.

## The Move screen overlay

Tap the overlay button to burn the Move's 128×64 screen into the video.

- **Drag** it to move it and **pinch** to resize it. What you see in the
  viewfinder is exactly what ends up in the file.
- Portrait and landscape each remember their own position and size.
- In Settings → **Move screen**:
  - **Opaque**: the screen as a black-and-white rectangle.
  - **Black clear**: black pixels are see-through, so only the lit pixels float
    over the picture (the default).
  - **White clear**: white pixels are see-through.
  - **Screen opacity**: fades the overlay.

## Settings

Tap ⚙ or the status chip. Tap the viewfinder to close it. Changes apply
immediately. Settings can't be opened while recording or saving.

**Move screen**: overlay transparency and opacity (see above).

**Video**: resolution **720p**, **1080p** (default) or **4K**, always at
30 fps. 4K files are about three times the size of 1080p ones.

**Audio**

| setting | default | what it does |
|---|---|---|
| YouTube −14 / Loud −9 | YouTube −14 | loudness target in LUFS. YouTube turns down anything louder than −14, so pick −9 only for platforms that don't |
| Master audio | on | high-pass, glue compressor, loudness normalisation, low-pass and a true-peak limiter at −1 dBTP. Off keeps the audio exactly as the Move played it |
| Glue compressor | on | gentle 2:1 compression before normalisation |
| Keep raw WAV | off | also saves the unmastered audio as a WAV |
| AI timeline | off | saves the hand movements and screens next to each take (see below) |

**Link Audio stems**: see the next section.

**Move host**: `move.local` by default. If the Move isn't found, type its IP
address (for example `192.168.1.42`) and press Done.

## Link Audio stems

When stems are on, every take also saves the separate channels of a Link
Audio session as 24-bit WAV files that line up with the video's first frame.
Drop them into a DAW at 0:00 and they match the picture.

1. On the Move, turn on **LINK** in movy's Settings. Ableton Live 12.2+ works
   too if it is on the same network.
2. In Schwung Cam's Settings, turn on **Record picked channels as WAVs**.
3. The available channels appear as switches, for example
   `Track 1 · Move`, `Master · Move`. Turn on the ones you want.
   Picks are remembered even when that device is offline. They're marked
   "not in the session" until it comes back.
4. Record as usual. The status chip shows how many of your picks are coming
   in, for example `Link 3/4 stems`, or `Link: alone` if no other Link
   device is found.

Stems are saved in `Music/SchwungCam/<take>/` with one WAV per channel.

## AI timeline

With **AI timeline** on, each take also saves
`Documents/SchwungCam/<take>.ai.zip`. It contains what your hands did on the
Move (pads, steps, knob turns, buttons, transport lights) timed to the video,
the Move screens around each gesture as PNG images, and a README that
explains the format. Give it to an AI assistant together with the video to
get captions or a description of how the track was built.

The Takes screen also uses it to draw a moving Move next to the video
(the Gestures tab).

## Takes: watching, sharing, deleting

Tap the last-take thumbnail to open **Takes**.

### The Takes grid

Takes are grouped by day, two per row, newest first. Each tile shows the
duration and small icons for what the take contains:

| icon | material |
|---|---|
| 🎞 movie | mastered video |
| 📹 videocam | raw video (only kept if saving failed) |
| 〰 graphic_eq | raw WAV |
| 🎚 instant_mix | stems |
| ✨ auto_awesome | AI timeline |

- **Tap** a take to open it.
- **Long-press** to start selecting, then tap more takes. The top bar shows
  the number and size, with **Share** and **Delete**.
  - **Delete** asks what to remove: **Everything**, or only some kinds
    (for example just stems or raw audio) from all selected takes.
- **⋮ → Free up space…** removes raw video, raw audio and stems from every
  take and keeps the mastered videos and AI timelines.

### A take

The video is at the top with one playhead (−10 s, play/pause, +10 s and a
scrubber) that drives the tabs below it:

- **Audio**: a waveform for the master, the raw WAV and each stem. Tap or drag
  a waveform to seek. Use the chips to choose what you hear: **Mastered**,
  **Raw**, or **Stems mix**. In Stems mix, each stem has **S**olo and
  **M**ute.
- **Gestures** (if the take has an AI timeline): a drawn Move that follows
  the video, showing its screen, the pads lighting up as they're hit, knob
  rings turning and the transport lights. Below it is a list of every event.
  Tap an event to jump to it.
- **Files**: every file of the take with its size and folder. Stems expand to
  one row per channel. Use the share icon on a row to send just that file.
  Long-press a row to select files, then share or delete only those.

**Fullscreen**: tap the fullscreen button, or turn the phone sideways while a
landscape take plays. The controls hide after 3 s; tap to show them again.
The ✨ button floats the Move screen over the video.

**Share** (top bar) opens a checklist of the take's materials with their
sizes, with the mastered video ticked. Tick what you want and send it to any
app. Nothing is zipped.

**Delete** moves files to the system trash and shows **Undo** for a few
seconds. Android empties the trash after 30 days. **⋮ → Delete permanently**
skips the trash.

## Where files go

All files are in the phone's shared storage, so other apps, the gallery and a
USB connection to a computer can see them.

| file | location |
|---|---|
| mastered video | `Movies/SchwungCam/SchwungCam_<date>_<time>.mp4` |
| raw WAV (if on) | `Music/SchwungCam/SchwungCam_<date>_<time>_raw.wav` |
| stems (if on) | `Music/SchwungCam/SchwungCam_<date>_<time>/<channel>.wav` |
| AI timeline (if on) | `Documents/SchwungCam/SchwungCam_<date>_<time>.ai.zip` |
| raw video (rescue) | `Movies/SchwungCam/SchwungCam_<date>_<time>_raw.mp4` |

The video is H.264 with AAC-LC audio at 384 kbps (or the highest rate your
phone's encoder supports), 44.1 kHz stereo.

## Troubleshooting

**"Move offline"**: the phone and the Move must be on the same network. If
`move.local` doesn't resolve on your network, enter the Move's IP address
under Settings → Move host.

**"No audio · turn on Mirror Display"**: turn on Mirror Display in Schwung's
settings on the Move.

**The video has silence in places**: the Wi-Fi dropped audio packets.
Silence fills the gaps so the sync holds. Move the phone closer to the router,
or use a less busy network or 5 GHz band.

**Saving takes a long time**: make sure you installed the release APK (from
the Releases page). Development (debug) builds master the audio many times
slower.

**A take shows only "raw video"**: saving failed and the app kept the raw
recording so nothing is lost. A dialog explains why it failed.

**Stems say "Link: alone"**: no other Link device was found. Turn on LINK in
movy (or Live), check that both are on the same network, and look in
Settings again for the channel list.

**Old takes are missing after reinstalling**: allow **Photos and videos** and
**Music and audio** access when Takes asks for it. AI timeline zips in
`Documents` can't be seen after a reinstall (Android only shows non-media
files to the app that made them). The files are still on the phone.

**Report a bug**: [GitHub Issues](https://github.com/DimaDake/schwung-cam/issues).
