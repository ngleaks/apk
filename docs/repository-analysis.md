# Source-repository analysis: `hanuramakuku/Pbb-Live-Stream`

Inspected 2026-11-10 (`git clone --depth 1`, HEAD of `main`).

## Layout

```
index.html      maintenance page ("PBB LIVE STUDIO — Maintenance")
script.js       obfuscated (javascript-obfuscator) player front-end  (~50 KB)
data.js         obfuscated channel table                             (~3.5 KB)
style.css       ~20 KB of glass-morphism styling for the old web UI
loadingpbb.jpeg, pinoy_ako.mp3
README.md       "Pbb-Live-Stream — Pbb All In Collab 3.0"
```

## What the front-end does (deobfuscated behaviour, not re-implemented for TV)

- The page loads `data.js`, which builds a 4-entry channel table (`CH`). Each entry is
  `[name, manifestUrl, keyPair, '']` where `keyPair` is two 32-hex strings joined with `:`.
  A helper (`uuid()`) reformats each 32-hex segment into 8-4-4-4-12 form — i.e. the pair is a
  **ClearKey keyId : key** in the shape EME's `org.w3.clearkey` expects.
- `script.js` selects a camera, initialises a DASH player (the string table references
  `org.w3.cle[arkey]`, i.e. `org.w3.clearkey`, via the dash.js protection API), and plays a live
  `.mpd`. The UI has a 4-camera grid mode, per-channel HD badges, a fullscreen mode, pinch-zoom,
  and the same anti-debug/watermark snippets as the maintenance page.
- Channels referenced by the obfuscated strings:

  | Name            | Host                       | Path pattern                                                        |
  |-----------------|----------------------------|---------------------------------------------------------------------|
  | PBB Main Live   | `abslive.akamaized.net`    | `/dash/live/2099522/pinoybigbrothercollabedition30live/manifest.mpd`|
  | PBB Dining Area | `absp-live.akamaized.net`  | `/dash/live/2099522/pbbcollab30allaccesslivestream-diningarea/manifest.mpd` |
  | PBB External Den| `absp-live.akamaized.net`  | `/dash/live/2099522/pbbcollab30allaccesslivestream-externalden/manifest.mpd` |
  | PBB All Access  | `absp-live.akamaized.net`  | `/dash/live/2099522/pbbcollab30allaccesslivestream/manifest.mpd`    |

  (Hostnames/paths are recorded for documentation; the embedded key pairs were deliberately
  not extracted into this document or into the app.)

## Why these streams are NOT shipped in the app

1. **Authorization.** These are ABS-CBN's PBB broadcasts. Nothing in the repository
   establishes a license to rebroadcast them; "Pbb-Live-Stream" is an unofficial viewer.
   PBB's own web player is rate-limited, ClearKey-protected and session-bound; the embedded
   keys exist so *the site's own visitors* can watch, not so a third party can repackage the
   broadcast inside an APK.
2. **DRM.** The streams are ClearKey-encrypted MPEG-DASH. The app would need a ClearKey-capable
   player with the provider's license terms. Handing those keys to arbitrary installs is exactly
   what DRM exists to prevent.
3. **Freshness.** The `index.html` is a maintenance page ("Updating Live Feeds… The studio
   broadcast will resume shortly"). The URLs and keys are expected to be rotated.
4. **Project rules.** This repository does not bundle, mirror or hardcode the stream URLs or
   keys in source, logs or APKs. The TV app ships with an empty (unconfigured) catalog and a
   single clearly-labelled public Apple bipbop HLS test stream.

## How the app handles this instead

- `assets/streams.json` defines the catalog with **empty URLs** for the four PBB slots.
- Every screen has a first-class "NOT CONFIGURED" state with configuration instructions.
- Authorized HLS/DASH URLs can be injected without rebuilding:
  `adb push streams.json <app-external-files-dir>/streams.json`, or rebuilt in via the asset,
  or opened ad-hoc through `pbblivetv://play?url=<https-url>&title=<name>` (see
  `docs/configuring-streams.md`).
- ClearKey/Widevine feeds require a Media3-based player (README → "DRM-protected feeds").
  The player is isolated behind `ph.pbb.live.player.LivePlayer`, so swapping it is a
  one-file change.
