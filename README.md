# PBBforFREE TV — Android TV / Google TV player

A small native TV build that replaces the phone-only WebView wrapper in `pbbforfree.apk`.

**Ready to install:** [`pbb-tv-1.0.0.apk`](pbb-tv-1.0.0.apk) (878 KB, minSdk 24, targetSdk 35).
Sideload it with `adb install pbb-tv-1.0.0.apk`, or download it on the TV with Downloader
(`https://raw.githubusercontent.com/ngleaks/apk/arena/0c5efdd6-apk/pbb-tv-1.0.0.apk`).

The original APK is a `freewebtoapk.com` template that opens `pbblivestream.vercel.app` in a
WebView. That cannot be driven by a TV remote, has no real leanback banner, no channel switching,
and drags in 7 MB of unused ad/IAP/push SDKs.

| | old `pbbforfree.apk` | this build |
|---|---|---|
| UI | remote website in a WebView | native full-screen player, custom drawn overlay |
| Remote / D-pad | not usable (no focus handling) | OK = play/pause, ← = channels, BACK = exit, ▲▼ = show chrome |
| Stream playback | whatever the page does | HLS live (segments) + progressive MP4/WebM, auto-retry |
| Launch entry points | phone launcher + leanback category | `LEANBACK_LAUNCHER` **and** `LAUNCHER` |
| TV banner | blank 320×180 placeholder | branded `res/drawable/tv_banner.png` |
| Permissions | 35 (camera, mic, location, phone, SMS, boot, …) | 2 (INTERNET, ACCESS_NETWORK_STATE) |
| Native bridges | hundreds of `@JavascriptInterface` methods | none — nothing remote is rendered |
| APK size | 23 MB unpacked / 10.9 MB download | 878 KB |
| Third-party libs | ~60 (Firebase, OneSignal, Unity, Facebook, Billing, ML Kit…) | **zero** |

## Build

Two ways, both produce the same APK:

```bash
# 1. offline pipeline: aapt2 -> javac -> d8 -> apksigner, no Gradle/Maven/SDK download
export JAVA_HOME=... ANDROID_JAR=... AAPT2=... D8_JAR=... APKSIGNER_JAR=... ECJ_JAR=...
buildtools/build_apk.sh                     # -> buildtools/dist/pbb-tv-<version>.apk

# 2. Gradle (needs a normal machine with the Android SDK)
gradle :app:assembleRelease
```

`tools.properties` lists the exact tool versions and where they come from (npmjs + pypi), so the
same command works in CI (see `docs/ci/build-tv-apk.yml`) and in a locked-down sandbox.

### Signing

* no key present → a local debug key is generated once in `buildtools/debug.keystore`;
* `release.keystore` + `keystore.properties` present (see `dump-signing.sh`) → signed with the
  **old APK's own key**, so the new build installs as an update over `com.webapp.pbbforfree`…
  only if the package name is changed back to `com.webapp.pbbforfree`; a different applicationId
  installs side by side. Play Store uploads need your own upload key either way.

## Channels

Channels are seeded from `StreamConfig.MANIFEST` on first launch, then live on the device:

| Action | How |
|---|---|
| Pick a channel | press ← (or MENU / CH+ / CH−) |
| Remove a channel | long-press OK on it |
| Add a channel without rebuilding | `adb shell am start -a android.intent.action.VIEW -d "pbblivetv://add?url=https://…/live.m3u8&title=Main" com.pbb.streamtv` |
| Deep link straight to a stream | `pbblivetv://play?url=…&title=Main` |
| Open the website | "Open website…" → system browser (never inside the app) |

The list ships with one clearly labelled demo stream (Apple's public HLS sample) so you can verify
the player on the TV immediately — long-press OK to delete it. Replace the `Main camera` /
`Dining area` / `External cam` placeholders with your own HLS URLs.

### HLS support

`android.media.MediaPlayer` only plays one progressive file, so `HlsPlaylist` + `SegmentPlayer`
implement the part of RFC 8216 that live TV needs: master playlists (best variant ≤ 12 Mbps),
rolling playlists with `#EXT-X-MEDIA-SEQUENCE`, 3-segment prefetch, manual retry and live-edge
recovery. Known limits (deliberate, keeps the APK under 1 MB with zero dependencies): no mid-stream
ABR, no trick play on live, brief gap if a segment stalls.

## Deliberately not included

* **No WebView.** A remote site cannot be focused with a remote; wrapping it is why the current APK
  is unusable on TV.
* **No ads / IAP / push SDKs.** They were disabled in the old config anyway.
* **No page scraping.** Point `MANIFEST` at streams you are allowed to rebroadcast.

⚠️ Content rights: "PBB" is ABS-CBN's. Ship this only with streams you own or are licensed to
rebroadcast, otherwise expect a Play Store takedown regardless of how good the player is.
