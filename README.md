# PBB LIVE — Android TV

A native, remote‑first Android TV application for watching PBB live cameras in a minimalist,
CCTV‑style interface — **not** a WebView wrapper.

| | |
|---|---|
| App ID | `ph.pbb.live` (debug: `ph.pbb.live.debug`) |
| Version | 2.0.0 (`versionCode` 200) |
| minSdk / targetSdk | 24 / 35 · landscape · LEANBACK_LAUNCHER + phone launcher |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE` — nothing else |
| Dependencies | **zero** third‑party libraries (platform `MediaPlayer`) |
| APKs | [`pbb-live-tv-2.0.0-release.apk`](pbb-live-tv-2.0.0-release.apk) (signed, v2/v3) · [`pbb-live-tv-2.0.0-debug.apk`](pbb-live-tv-2.0.0-debug.apk) |
| Size | ~1.4 MB each |

Install: `adb install pbb-live-tv-2.0.0-release.apk` — or sideload on the TV.

⚠️ **Content rights.** “PBB”, “Pinoy Big Brother” and the broadcasts are ABS‑CBN's. This build
ships **without** any stream URLs or keys (see
[docs/repository-analysis.md](docs/repository-analysis.md)). Configure only feeds you are
authorized to rebroadcast. See [docs/configuring-streams.md](docs/configuring-streams.md).

---

## What the source repository is (and what this app does with it)

`github.com/hanuramakuku/Pbb-Live-Stream` is a web app: a single `index.html` that is currently
a maintenance page (“Updating Live Feeds”), an obfuscated `script.js` (dash.js front‑end), and an
obfuscated `data.js` containing four ABS‑CBN Akamai MPEG‑DASH manifest URLs plus embedded
**ClearKey** key pairs (the player uses EME `org.w3.clearkey` — the URLs are ClearKey‑encrypted,
not merely token‑gated).

Those URLs/keys are **not** carried into this app: the repository shows no license to
rebroadcast them, the site is under maintenance, the keys are expected to rotate, and shipping
ClearKey material in an APK would defeat the provider's copy protection. The full analysis —
including why — is in **[docs/repository-analysis.md](docs/repository-analysis.md)**.

Instead, the app implements the whole architecture (catalog → per‑section player → CCTV
multi‑view) and ships with an empty authorized‑stream configuration plus a clearly‑labelled
public Apple bipbop HLS **demo** camera so the pipeline can be verified immediately. Inject your
own authorized URLs as described below.

## Features

- Four sections, always available: **MAIN LIVE · DINING · EXTERNAL · ALL** (bottom navigation,
  exactly four items).
- **MAIN LIVE** is the default screen; auto‑loads the authorized Main Live feed when one is
  configured, and shows a first‑class NOT‑CONFIGURED state otherwise (with one‑key demo path).
- **DINING / EXTERNAL**: selectable camera cards for every feed configured in that section.
- **ALL — CCTV multi‑view**: 2×2 grid of 16:9 tiles, up to 4 feeds per page, LEFT/RIGHT page
  flipping at row edges, per‑tile label + status (LIVE/DEMO/NOT CONFIGURED), strong focus ring,
  page dots, up to **four simultaneous players** (focused tile unmuted, rest muted, CCTV‑style).
  OK opens a tile fullscreen; BACK returns to the grid with the same tile focused. The last‑
  focused tile is remembered across launches.
- Player states everywhere: connecting, buffering, paused, error (+ RETRY, no URL leakage in
  error text), stream‑ended, and not‑configured. Retry reloads the same feed.
- **Auto‑hiding bottom navigation** (see below) with fade+slide animation.
- Remote‑first: D‑pad navigation, OK, BACK, focus restoration, never a focus trap.
- Live aspect ratio handling (letterboxed 16:9, correct for portrait/4:3 too).
- Player lifecycle: players are released whenever a screen hides; switching sections never
  leaves decoders running in the background; single‑instance `MediaPlayer` per visible feed.
- Deep link: `pbblivetv://play?url=<https-url>&title=<name>` opens a stream fullscreen.

## Auto‑hiding bottom navigation

- Shown on launch; hides itself after **4 s** without remote interaction.
- D‑pad **DOWN** reveals it (and moves focus to it) unless the content consumed the DOWN
  (e.g. moving between cards/rows).
- **OK/LEFT/RIGHT** select/navigate the four items when the bar is visible/focused.
- It never hides while a navigation item has focus, and never leaves a gap — the bar is an
  overlay, so the video keeps the full screen the whole time.
- Visible state: dark translucent scrim. Hidden state: video only, no chrome.

## Remote controls

| Input | Action |
|---|---|
| D‑pad | Move between tiles / cards / nav items |
| DPAD‑DOWN | Reveal bottom bar (on player/fullscreen too) |
| OK (`DPAD_CENTER`/`ENTER`) | Activate focused control; on video — play/pause + toggle chrome |
| BACK | Close overlay → return to grid/list · hide bar · else exit |
| DPAD‑UP | Leave the bar, focus content |

## Configuring authorized streams

Three ways (precedence: deep link > drop‑in override > rebuilt asset):

1. **Rebuild**: edit `app/src/main/assets/streams.json` → rebuild.
2. **Drop‑in override** (no rebuild): push a `streams.json` to the app's external files dir, e.g.
   `adb push my-streams.json /sdcard/Android/data/ph.pbb.live/files/streams.json`
3. **Ad‑hoc**: `adb shell am start -a android.intent.action.VIEW -d "pbblivetv://play?url=https://…/live.m3u8&title=Main" ph.pbb.live`

Details, formats and ClearKey/DRM notes: **[docs/configuring-streams.md](docs/configuring-streams.md)**
(explains why the PBB studio streams are ClearKey DASH and how a Media3 build would integrate an authorized DRM license).

### DRM‑protected feeds

The built‑in player (platform `MediaPlayer`) cannot decrypt ClearKey/Widevine. If you hold a
license for such feeds, the supported path is AndroidX Media3 behind the existing
`ph.pbb.live.player.LivePlayer` interface — see "DRM-protected feeds" in
[docs/configuring-streams.md](docs/configuring-streams.md). Don't paste DRM keys into source.

## Build

### A. Normal machine (Android Studio / CLI)

Requirements: JDK 17, Android SDK with platform 35.

```bash
gradle :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
gradle :app:assembleRelease     # signed with keystore.properties when present, else debug key
```

The same source builds with Gradle (AGP 8.11.0, Kotlin 2.0.21) or with the offline pipeline —
the code uses only framework APIs.

### B. Locked‑down sandbox (no SDK/Gradle downloads)

`buildtools/build_apk.sh` replicates the Gradle pipeline: `aapt2 → ecj (R) → kotlinc → d8 →
apksigner`. Tools come from npmjs/pypi/github.com (see `tools.properties`):

```bash
pip install --target=buildtools/pylibs jdk4py==17.0.9.2
npm install --prefix=buildtools/npm aaptjs3 @drxiaozhi/minapk
curl -sL https://registry.npmjs.org/kotlin-compiler/-/kotlin-compiler-2.4.21.tgz | tar xz -C buildtools/

export JAVA_HOME=$PWD/buildtools/pylibs/jdk4py/java-runtime
export ANDROID_JAR=$PWD/buildtools/npm/node_modules/@drxiaozhi/minapk/tools/android.jar
export AAPT2=$PWD/buildtools/npm/node_modules/aaptjs3/bin/x64/linux/aapt2
export D8_JAR=$PWD/buildtools/npm/node_modules/@drxiaozhi/minapk/tools/d8.jar
export APKSIGNER_JAR=$PWD/buildtools/npm/node_modules/@drxiaozhi/minapk/tools/apksigner.jar
export ECJ_JAR=$PWD/buildtools/npm/node_modules/@drxiaozhi/minapk/tools/ecj-3.45.0.jar
export KOTLINC_HOME=$PWD/buildtools/package

buildtools/build_apk.sh              # release (signed if keystore present)
buildtools/build_apk.sh --debug      # debug key (auto-generated in buildtools/)
```

CI: [docs/ci/build-tv-apk.yml](docs/ci/build-tv-apk.yml) runs both pipelines on GitHub Actions.

## Signing

This repository contains **no** keystores. `release.keystore` and `keystore.properties` are
git‑ignored; the release APK shipped here was signed with a self‑signed **PBB LIVE** key
(created 2026‑11‑10, stored only on the build machine — back it up if you want to ship updates;
losing it means future APKs can't install as updates over this one).

Create your own release key:

```bash
keytool -genkeypair -keystore release.keystore -alias pbblive \
  -keyalg RSA -keysize 2048 -validity 10950 -dname "CN=PBB LIVE, O=<you>, C=PH"
cat > keystore.properties <<'EOX'
storeFile=release.keystore
storePassword=…same as -storepass you used…
keyAlias=pbblive
keyPassword=…
EOX
```

Rules: never commit `*.keystore`/`keystore.properties` (`.gitignore` already covers them); use
PKCS12 (default in JDK 17); back up the keypair offline; for Play Store, use an upload key and
enroll Play App Signing. Verify with `apksigner verify --print-certs your.apk`.

## Repository layout

```
app/src/main/java/ph/pbb/live/
  MainActivity.kt            host: screens, overlay, key dispatch, nav auto-hide timers
  config/                    CameraFeed · StreamRepository (streams.json + override + deep link) · Prefs
  player/                    LivePlayer (interface) · MediaLivePlayer (MediaPlayer impl) · PlaybackState
  ui/                        BottomNavView · PlayerStageView · CameraTileView · FeedCardView · TvScreen
  ui/screens/                MainLiveScreen · SectionListScreen (DINING/EXTERNAL) · AllGridScreen (ALL grid) · PlayerOverlayScreen
  ui/…/util/                 AspectFitFrameLayout · focus/density/TV-click helpers
app/src/main/assets/streams.json   camera catalog (PBB slots empty by design + 1 public demo)
app/src/main/res/                  banner, launcher icons, strings/colors/themes
buildtools/build_apk.sh            offline aapt2→kotlinc→d8→apksigner pipeline
tools.properties                   pinned tool list for the offline build
docs/                             repository-analysis · configuring-streams · manual-test-plan · CI
```

## Verified vs. pending

Built, packaged and signature‑verified in the build sandbox (v2+v3, `apksigner verify`).
**Not executed on a device/emulator in this environment** — runtime/focus verification is
enumerated in [docs/manual-test-plan.md](docs/manual-test-plan.md) and should be run on a
1080p Android TV target. Known runtime characteristics: platform MediaPlayer DASH support
varies by device; the bipbop demo requires internet; 4K decode of 4 streams is hardware‑bound.

## Known limitations / missing authorized configuration

- No PBB/ABS‑CBN URLs or ClearKey material is bundled (authorization not established; site is
  under maintenance) — every PBB tile ships as NOT CONFIGURED until you add authorized URLs.
- Platform MediaPlayer: no DRM, no LL‑HLS/sub‑second tuning, single ABR decision, live‑edge
  recovery depends on device. For those, use the Media3 path (see docs).
- Simultaneous grid playback is capped at 4 and falls back to single‑active on low‑RAM boxes.
- Fullscreen from ALL releases (not parks) the grid's players; returning reconnects them.
- Focus restoration for ALL remembers the last tile index, not the page index separately.
- On launcher restart after a crash, state resets to MAIN LIVE (by design).

## Version history

- **2.0.0 (2026‑11‑10)** — full rewrite as PBB LIVE: CCTV multi‑view, four‑section navigation,
  auto‑hiding bar, MediaPlayer playback, signed release APK. (Supersedes `com.pbb.streamtv` 1.0.x.)
- **1.0.0** — `com.pbb.streamtv` minimal player (git history; unlisted here).

⚠️ Content rights: "PBB" is ABS‑CBN's. Ship only streams you own or are licensed to rebroadcast.
