# Configuring authorized streams

PBB LIVE ships **no** PBB stream URLs. You supply your own authorized URLs in one of three ways.
All three feed the same catalog; precedence = (3) > (1) > built-in (2 is a runtime fallback).

> Use only streams you own or are licensed to rebroadcast. See `docs/repository-analysis.md`.

## 1. Rebuild with the asset edited (permanent)

Edit `app/src/main/assets/streams.json`:

```json
{
  "version": 2,
  "cameras": [
    { "id": "main_live",   "label": "PBB Main Live",   "shortLabel": "MAIN LIVE", "section": "main",     "url": "https://cdn.example.com/main/manifest.m3u8", "format": "hls",  "enabled": true },
    { "id": "dining",      "label": "PBB Dining Area", "shortLabel": "DINING",    "section": "dining",   "url": "",                                        "format": "auto", "enabled": true },
    { "id": "external_den","label": "PBB External Den","shortLabel": "EXTERNAL",  "section": "external", "url": "",                                        "format": "auto", "enabled": true }
  ]
}
```

Fields per camera:

| field        | required | notes |
|--------------|----------|-------|
| `id`         | yes      | unique; used for focus restoration |
| `label`      | yes      | full name, shown in the player chrome |
| `shortLabel` | no       | ≤ 28 chars, shown on tiles/cards |
| `section`    | yes      | `main` ‥ MAIN LIVE · `dining` ‥ DINING · `external` ‥ EXTERNAL · `all` ‥ ALL grid |
| `url`        | yes*     | `https://…` (empty = “NOT CONFIGURED” tile/card) |
| `format`     | no       | `auto` (default), `hls`, `dash`, `progressive` — the platform player sniffs the manifest |
| `enabled`    | no       | `false` hides the tile from playback but keeps the slot |
| `demo`       | no       | shows a DEMO badge |
| `drm`        | no       | any `drm` object marks the feed as DRM-protected → see “DRM-protected feeds” below |

Then rebuild (`README` → Build) and reinstall.

## 2. Drop-in override on the device (no rebuild)

```bash
adb push streams.json "/sdcard/Android/data/ph.pbb.live/files/streams.json"
```
If that path is not writable on your firmware, use the app-specific external files dir shown by
`adb shell ls /storage/emulated/0/Android/data/ph.pbb.live/files` (no root required).
The override **replaces** the bundled catalog entirely.

## 3. Deep link (ad-hoc, one stream)

```bash
adb shell am start -a android.intent.action.VIEW -d "pbblivetv://play?url=https%3A%2F%2Fcdn.example.com%2Flive.m3u8&title=Main%20Camera" ph.pbb.live
```
Opens the stream fullscreen immediately. `url` must be `http(s)://`-schemed; anything else is ignored.

## Formats

- **HLS (`.m3u8`)** — best supported; VOD/live playlists with the platform player.
- **MPEG-DASH (`.mpd`)** — depends on the device's platform DASH support (API 21+); if a live
  manifest fails, the error panel's RETRY is the right loop. Some DRM'd DASH (see below) needs Media3.
- **Progressive MP4/WebM** — plays fine; no adaptive switching.

Stream segments must be reachable from the TV (most missed setups are certificate/VLAN issues).
The app does not accept `http://` by default (cleartext is disabled; targetSdk 35).

## DRM-protected feeds (ClearKey / Widevine)

The current player is built on the platform `MediaPlayer` (zero dependencies, ~1.4 MB APK). It
**cannot decrypt DRM**. The PBB studio streams are ClearKey (`org.w3.clearkey`) MPEG-DASH, which
is why the app ships without them.

If you hold a legitimate license (e.g. a distributor agreement), the supported path is:

1. Switch the project to AndroidX Media3 (`build.gradle.kts` has commented dependencies):
   `media3-exoplayer`, `media3-exoplayer-dash`, `media3-exoplayer-hls`, `media3-exoplayer-ui`.
2. Replace `ph.pbb.live.player.MediaLivePlayer` (it implements `ph.pbb.live.player.LivePlayer`)
   with a Media3-backed implementation and register `LocalMediaDrmCallback` / your provider's
   `MediaDrmCallback` with the configuration issued to you.
3. Every screen stays untouched — they only see `LivePlayer`.

Do not paste ClearKey key pairs into this repository, into `streams.json`, or into release builds.
License callbacks belong in the app module you control, with the provider's written authorization.

## Multiple ALL-grid cameras

Feeds with `section: "all"` appear in ALL. Up to four render per page, two pages maximum is
typical; LEFT/RIGHT past the row edge flips pages. Up to four simultaneous players are allowed
(one muted unless focused); low-RAM TV boxes automatically fall back to single-active mode
(`grid_mode=single` in SharedPreferences, or `auto` default).
