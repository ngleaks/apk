# PBBforFREE TV — Android TV / Google TV player

A small native TV build that replaces the phone-only WebView wrapper in `pbbforfree.apk`.

**Ready to install:** [`pbb-tv-1.1.0.apk`](pbb-tv-1.1.0.apk) (882 KB, minSdk 24, targetSdk 35).
Sideload it with `adb install pbb-tv-1.1.0.apk`, or download it on the TV with Downloader from the
raw link on branch `arena/d7765815-apk`
(`https://raw.githubusercontent.com/ngleaks/apk/arena/d7765815-apk/pbb-tv-1.1.0.apk`).
Upgrading from 1.0.0 keeps the same signing key, so it installs over it.

The original APK is a `freewebtoapk.com` template that opens `pbblivestream.vercel.app` in a
WebView. That cannot be driven by a TV remote, has no real leanback banner, no channel switching,
and drags in 7 MB of unused ad/IAP/push SDKs.

| | old `pbbforfree.apk` | this build |
|---|---|---|
| UI | remote website in a WebView | native 10-foot home screen: 3 camera cards + action row |
| Remote / D-pad | not usable (no focus handling) | OK = watch, ← → = pick, ▲ ▼ = move, BACK = home |
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

## Channels — the three PBB cameras

The home screen mirrors the camera switcher on `pbblivestream.vercel.app`, but native and D-pad
first: **Main stream · All Access**, **Dining area**, **External den**. Each card is one camera
slot with a stable key (`all`, `dining`, `external`); a slot is *bound* to a real stream URL once,
and that binding is kept on the device across app updates.

| Action | How |
|---|---|
| Watch a camera | focus its card, press OK |
| Bind / change its link | OK on a card that says `NOT SET` (or OK on the error card while watching) — a URL box opens with the on-screen keyboard |
| Add an extra stream | "Add a stream" row, or the deep link below |
| Bind from a PC (no typing on the TV) | `adb shell am start -a android.intent.action.VIEW -d "pbblivetv://add?key=dining&url=https://…/live.m3u8" com.pbb.streamtv` |
| Deep link straight to playback | `pbblivetv://play?url=https://…/live.m3u8&title=Dining` |
| Bake a link into the APK | fill the URL in `StreamConfig.MANIFEST` and rebuild |
| Remove | long-press OK on a card (camera slots can be restored with "Restore cameras") |
| Open the website | "PBB website" row → system browser (never inside the app) |

`key` must be `all`, `dining` or `external`; omit it and the URL is saved as an extra channel.

The app ships with **no** PBB URLs: they are behind iWant and not public (Main is free there,
Dining/External are behind a subscription). The one pre-filled row is a clearly labelled
`Demo · HLS test stream` (Apple's public sample) so you can prove the TV, the network and the
decoders work before pasting a real link.

### Where the links come from

The app does not scrape iWant and has no accounts: whatever URL you paste is played as-is, so the
stream has to be one you can reach **without a login** (an open HLS/DASH URL, including one your
own `pbblivestream` deployment already holds). If the CDN returns 401/403 the card will say
"Stream unreachable" and retry — that means the link is bound to a session, not that the app is
broken.

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
* **No page scraping and no login.** Point the camera slots at streams you are allowed to
  rebroadcast; there is no credential storage, no WebView cookie jar and no account handling.

⚠️ Content rights: "PBB" is ABS-CBN's. Ship this only with streams you own or are licensed to
rebroadcast, otherwise expect a Play Store takedown regardless of how good the player is.
