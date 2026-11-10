# Upstream analysis: `hanuramakuku/Pbb-Live-Stream`

Analysed at commit `HEAD` of `main` (cloned into a scratch directory; nothing from the site is
bundled in the APK). Files: `index.html`, `script.js` (49 KB, obfuscated), `data.js` (3.5 KB,
obfuscated), `style.css`, `loadingpbb.jpeg`, `pinoy_ako.mp3`, `README.md`.

## 1. The site is currently in maintenance mode

`index.html` no longer contains a player. It renders a "PBB LIVE STUDIO – Maintenance" card:

> **System Update** · *Updating Live Feeds* — "We are currently refreshing the server keys to bring
> you the latest streams. The studio broadcast will resume shortly."

There are no `<video>` elements, no player script tags and no channel buttons in the served HTML. The
player code still exists in `script.js`, so the deployment is a temporary swap, not a rewrite. **This
app therefore cannot (and does not) assume those feeds are up.**

## 2. Channel structure (`data.js`)

`data.js` obfuscates a single constant with a shuffled string table:

```js
const CH = [
  [label, url, "kid:key", ""],
  …
];
```

Decoded with Node (`node -e "globalThis.CH=…; eval(src)"`), the table is:

| # | Label | URL (Akamai, path truncated) | Third field |
|---|---|---|---|
| 1 | `PBB All Access` | `https://absp-live.akamaized.net/dash/live/2099522/pbbcollab30allaccesslivestream/manifest.mpd` | `kid:key` |
| 2 | `PBB Dining Area` | `…/pbbcollab30allaccesslivestream-diningarea/manifest.mpd` | `kid:key` |
| 3 | `PBB External Den` | `…/pbbcollab30allaccesslivestream-externalden/manifest.mpd` | `kid:key` |
| 4 | `PBB Main Live` | `https://abslive.akamaized.net/dash/live/2099522/pinoybigbrothercollabedition30live/manifest.mpd` | `kid:key` |

The `kid:key` values are 32-hex ClearKey pairs. They are **not** reproduced here and are **not** in
the app: they are the broadcaster's access control for those manifests, rotate without notice, and
shipping them would be both a rights problem and a broken build within days.

## 3. Playback stack

`script.js` is obfuscated but the structure is unambiguous:

* Shaka Player: `new shaka.Player()`, `await player.attach(vid)`, `shaka.polyfill.installAll()`.
* `player.configure({ drm: { clearKeys: { [kid]: key } } })` — the third `CH` field is used as
  ClearKey material, i.e. the manifests are **MPEG-DASH with ClearKey** (no Widevine licence server
  is involved).
* Load is `player.load(url)`; a watchdog re-loads on error/timeouts.
* A grid mode creates several `shaka.Player` instances for a 2×2 view — the idea the ALL screen in
  this app implements natively.
* `script.js` also does mobile-only things that make the site unusable on a TV: pinch-zoom/rotation
  touches on the stage, double-tap zoom, key handlers, a background-music fade (`pinoy_ako.mp3`), a
  viewer "counter", and "anti-debugging" key blocking.

## 4. Formats and protocols present

| Protocol | Present | Notes |
|---|---|---|
| MPEG-DASH (`.mpd`) | **yes — all four feeds** | adaptive, live, CMAF, Akamai; ClearKey |
| HLS (`.m3u8`) | no | — |
| Progressive MP4/WebM | no | — |
| RTMP / RTSP | no | — |

## 5. Authentication / subscription

* No login, no tokens, no cookies, no paywall in the page.
* The only access control is the **ClearKey DRM** on the DASH manifests.
* The site's own "maintenance" copy says the server keys are being rotated, which matches how
  ClearKey distributions usually work.

So: **no legitimate unauthenticated playback path exists for these particular feeds**, and this app
does not try to invent one. It ships them as catalogue entries in the `Licence required` state, and
plays anything you legitimately add (HLS or progressive today, DASH via the Media3 drop-in described
in the README).

## 6. Mapping upstream → this app

| Upstream | This app |
|---|---|
| `CH` table | `app/src/main/assets/streams.json` (same labels/sections, no keys) |
| Camera switcher (Main / Dining / External / 2×2) | bottom navigation: MAIN LIVE / DINING / EXTERNAL / ALL |
| Shaka + ClearKey | `Camera.keyId`/`key` fields → `Licence required` until supplied |
| 2×2 grid of Shaka instances | `AllScreen` 2×2 paged wall, ≤4 streams, one decoder per tile |
| Touch gestures, zoom, rotation | D-pad only, fixed 16:9 letterbox |
| Background music, watermark, counters | not ported (nothing to gain on a TV, more code to break) |
| Maintenance page | offline / unavailable states with retry |
