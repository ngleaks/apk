# Testing

## What was run here

This environment has no Android emulator, no device and no Android SDK, so verification is a mix of
builds, static inspection and a real logic test suite. Everything below was executed on the commit
that produced the APKs:

| # | Check | Result |
|---|---|---|
| 1 | Offline build (aapt2 → javac → d8 → apksigner), debug + release | pass |
| 2 | `apksigner verify --print-certs` on both APKs (v1+v2+v3) | pass — `CN=PBB Live TV Debug` |
| 3 | `aapt2 dump badging`: package, version, min/target SDK, label, banner, launcher | pass — `com.pbb.livetv`, 24/35, `LEANBACK_LAUNCHER` + `LAUNCHER`, `tv_banner.png` |
| 4 | Manifest permission audit | exactly `INTERNET` + `ACCESS_NETWORK_STATE` |
| 5 | APK content audit | 25 entries, 1 dex, `assets/streams.json` present, no keystores/tokens |
| 6 | `buildtools/test_core.sh` — 80 logic checks | pass (0 failures) |

### Logic suite (`buildtools/VerifyCore.java`)

| Area | Cases |
|---|---|
| 2×2 D-pad navigation | all four directions, all four edges, no wrap, hole skipping, awkward occupancy, 3-camera page |
| Grid paging | 0/4/5/9 cameras, page clamping, first occupied slot |
| 16:9 layout | wide/tall inputs, 2×2 cells at 1920×1080 (no overflow, ratio preserved), centring offsets |
| HLS master playlist | variant parsing, 1080p preference, 720p/360p caps, smallest-when-all-too-big, URL resolution, AES-128 detection |
| HLS media playlist | segment count, `EXT-X-MEDIA-SEQUENCE` numbering, durations, target duration, `ENDLIST`, encryption |
| Camera rules | empty URL → not playable, keyless DASH → `Licence required`, keys → playable, section filtering, lookup |
| URL classification | `.mpd`, `/dash/`, `format=mpd`, `.m3u8`, `.mp4`, query tokens |

The suite earned its keep: it caught a real bug where HLS segment sequence numbers ignored
`EXT-X-MEDIA-SEQUENCE` (which would have caused segment skipping loops on a rolling live playlist),
plus two off-by-one expectations.

## On-device matrix (run before shipping to a TV)

Signing, packaging and layout are verified; the items below need real hardware. Use an Android TV
emulator (API 30+) or a TV box, and `adb shell input keyevent <CODE>` to drive it.

### Launch & sections
- [ ] Cold start opens **MAIN LIVE** directly (no splash, no onboarding).
- [ ] ◀ ▶ on content cycles MAIN LIVE → DINING → EXTERNAL → ALL.
- [ ] ▼ from content focuses the nav bar; ◀ ▶ there changes section; ▲ returns to content.
- [ ] Section header, subtitle and nav highlight match the selected section.
- [ ] Kill and relaunch: last section (and last ALL camera) is restored.

### Playback
- [ ] HLS camera: `Connecting…` → picture within a few seconds, no stretched image.
- [ ] OK shows controls; Pause/Play, Mute, Next camera, Close all work; controls auto-hide after ~6 s.
- [ ] Picture keeps 16:9 on 720p, 1080p and 4K outputs (letterboxed, never cropped/stretched).
- [ ] Switching camera releases the previous decoder (check `adb shell dumpsys media.player`).
- [ ] Backgrounding the app stops audio; returning resumes.

### ALL / CCTV wall
- [ ] 2×2 grid, four labels + `CAM nn` badges, accent focus ring on the focused tile.
- [ ] ◀ ▶ ▲ ▼ move between tiles; edges do not wrap; ▼ from the bottom row reaches the nav bar.
- [ ] OK opens the focused camera fullscreen with sound; BACK returns to the wall with the same tile
      focused and the other tiles still streaming.
- [ ] More than 4 cameras: `PAGE n / m`, page 2 renders its tiles.
- [ ] Grid audio stays muted; opening a camera brings audio for that camera only.

### Failure handling
- [ ] Airplane mode while playing: error state with reason, Retry button reachable by remote.
- [ ] Reconnect: Retry recovers; HLS reconnects by itself after a short drop.
- [ ] Camera with empty `url`: "No stream configured" (no spinner, no fake picture).
- [ ] Keyless DASH camera: "Licence required" (no crash, no DRM bypass attempt).
- [ ] Rotate/undock/HDMI handoff does not strand the app with no focus.

### Remote edge cases
- [ ] Every screen can reach BACK (no focus trap); BACK twice on MAIN LIVE exits.
- [ ] Media play/pause keys work on the player; volume keys still show system volume.
- [ ] Holding a D-pad direction does not cause runaway section switching.

## How to reproduce the automated parts

```bash
source buildtools/install_tools.sh
buildtools/test_core.sh
buildtools/build_apk.sh --debug
buildtools/build_apk.sh
apksigner verify --print-certs pbb-live-tv-2.0.0-release.apk
```
