# Reference material

Analysis artefacts kept for traceability. They are **not** part of the Android build, and no stream
keys or third-party binaries live here.

* The upstream `hanuramakuku/Pbb-Live-Stream` sources (`index.html`, `script.js`, `data.js`,
  `style.css`) are not vendored: they are obfuscated and copied rather than analysed. Everything
  that matters about them is written down in [`../streaming-architecture.md`](../streaming-architecture.md).
* The original `pbbforfree.apk` (a `freewebtoapk.com` WebView wrapper around
  `pbblivestream.vercel.app`) and the previous single-screen `pbb-tv-1.0.0.apk` were removed from
  the repository root so the only APKs here are the ones built from this source tree:

  | APK | Package | Size | Signer |
  |---|---|---|---|
  | `pbb-live-tv-2.0.0-release.apk` | `com.pbb.livetv` | ~186 KB | `CN=PBB Live TV Debug` (local key) |
  | `pbb-live-tv-2.0.0-debug.apk` | `com.pbb.livetv.debug` | ~186 KB | same local key |

  The old package `com.pbb.streamtv` (v1) is unrelated to these; this is a different application id,
  so it installs side by side and can be uninstalled.
