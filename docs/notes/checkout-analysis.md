# What is inside `pbbforfree.apk`

Reverse-engineered notes for the original upload (kept at the repo root for reference).

## Identity

| | |
|---|---|
| Package | `com.webapp.pbbforfree` |
| Name / version | PBBforFREE · 1.0.0 (versionCode 10000) |
| SDK | minSdk 24, targetSdk 36, compileSdk 36 |
| Signer | `CN=com.webapp.pbbforfree`, self-signed 2026-04-20 → 2046, APK Signature Scheme v2 only |
| Main activity | `com.webtoapk.template.SplashActivity` → `com.webtoapk.template.WebViewActivity` |
| Size | 10.9 MB download / 23 MB unpacked, 1020 entries, 2 dex (7 MB code) |

`META-INF/MANIFEST.MF` says `Created-By: 1.0 (Web2APK Generator)`; the whole template lives in
`com.webtoapk.template.*` and `assets/native-bridge.js` is unmodified Capacitor. This is a
[freewebtoapk.com](https://freewebtoapk.com) build.

## The app is a shell around one URL

`assets/config.json` is AES-128-CBC encrypted behind an `ENCF` magic header. The key and IV are
plain `byte[]` fields of `com.webtoapk.template.AppConfig` in `classes2.dex`
(`byte[] h2 = "ENCF"`, key `i2`, iv `j2`), so decryption is trivial — the encryption only stops
casual browsing.

Decrypted highlights:

```
websiteUrl        https://pbblivestream.vercel.app/     appMode: "url"   bundledMode: false
enableAds         false        enableOneSignal false   enableIap false   isPremium true
enableOfflineCache false       enablePersistentCache true
enableSideMenu    true         enableBottomNav true (style "controls")    enablePipMode true
permCamera/Mic/Location/Storage/Phone true      background.keepRunning true, startOnBoot true
youtubeLink       https://youtube.com/@freakzyofficial    telegramLink https://t.me/PBBforFREE
```

The site itself is a Next.js "PBB STREAM — PBB3.0 Live" page (camera switcher: Main / Dining /
External / 2×2 grid). Nothing is bundled in the APK: with no network the app shows nothing.

## Why it feels bad on a TV

* The whole UI is the mobile website rendered in a WebView with `setAllowFileAccess(true)`,
  `setAllowFileAccessFromFileURLs(true)`, `setAllowUniversalAccessFromFileURLs(true)` and
  mixed content allowed — and no focus handling, so a remote can only scroll it.
* `WebViewActivity` is `screenOrientation` unset/portrait, 4:3-ish web layout on a TV screen.
* Leanback is only an *optional* feature and there is no real banner:
  `@drawable/appmint_tv_banner` is a 320×180 **blank** dark rectangle.
* 35 permissions (camera, mic, fine location, CALL_PHONE, boot receiver, badge permissions …)
  and SDKs it never uses: Unity Ads, Facebook, Firebase, OneSignal, Play Billing, ML Kit. Those
  plus the template are the 7 MB of dex.
* Six `@JavascriptInterface` objects (`WebToApk`, `__appmintPageBars`, `__appmintPipVideo`,
  `__AppMintCrash`, `AndroidNFC`) expose hundreds of native methods to whatever the web page
  loads.

## Threat read

No packer, no dropper, no hidden payload, no suspicious endpoints in the app's own code — the
network strings (`push.freewebtoapk.com`, `gateway.unityads.unity3d.com`, Google/Firebase hosts)
all belong to the bundled SDKs. The interesting risk is the WebView setup above plus the wide
permission set, not malware. Publishing an "PBB" livestream wrapper is a separate legal problem:
the name and content belong to ABS-CBN.
