# PBBforFREE TV — Android TV / Google TV player

A small, native TV build that replaces the phone-only WebView wrapper in `pbbforfree.apk`.

The original APK is a `freewebtoapk.com` template that opens `pbblivestream.vercel.app` in a
WebView. That cannot be driven by a TV remote, has no leanback banner, no channel switching and
drags in 7 MB of unused ad/IAP/push SDKs. This project is the TV-first alternative:

| | old `pbbforfree.apk` | this build |
|---|---|---|
| UI | remote website in a WebView | native full-screen Media3 player |
| Remote / D-pad | not usable (no focus handling) | OK = play/pause, ← = channels, BACK = exit |
| Stream playback | whatever the page does | ExoPlayer: HLS, DASH, MP4, audio focus, retry/backoff |
| Launch entry points | phone launcher + leanback category | `LEANBACK_LAUNCHER` **and** `LAUNCHER` |
| TV banner | blank 320×180 placeholder | branded `drawable/tv_banner.png` |
| Permissions | 35 (camera, mic, location, phone, SMS, boot, …) | 2 (INTERNET, ACCESS_NETWORK_STATE) |
| Native bridges | hundreds of `@JavascriptInterface` methods | none — no remote HTML is rendered |
| APK size | 23 MB | ~3 MB |

## Build

```bash
# locally (JDK 17 + Android SDK 35)
gradle :app:assembleRelease        # -> app/build/outputs/apk/release/

# or in CI: push to main / run the "Build TV APK" workflow, then download the artifact
```

The release build is minified and resource-shrunk. Signing:

* no `keystore.properties` → signed with the debug key (installs fine, not Play-ready);
* `keystore.properties` present (see below) → signed with your own key.

### Keep the old signature (optional, recommended)

`pbbforfree.apk` is self-signed (`CN=com.webapp.pbbforfree`). Extracting that key lets the new
build install **as an update** over the old app instead of needing an uninstall:

```bash
./dump-signing.sh pbbforfree.apk     # writes release.keystore + keystore.properties (git-ignored)
base64 -w0 release.keystore          # add as repo secret KEYSTORE_B64
cat keystore.properties              # add as repo secret KEYSTORE_PROPERTIES
```

Back those two files up. Losing the key means no future build can update an installed copy.

## Channels

Channels live in `app/src/main/java/com/pbb/streamtv/StreamConfig.java`:

```java
static final Stream[] MANIFEST = new Stream[]{
        new Stream("Main camera",   "https://…/main.m3u8", ""),
        new Stream("Dining area",   "https://…/dining.m3u8", ""),
        new Stream("External cam",  "https://…/ext.m3u8", ""),
};
```

They are seeded into SharedPreferences on first launch and can then be managed on the device:

| Action | How |
|---|---|
| Pick a channel | press ← (or MENU / CH+ / CH−) |
| Remove a channel | long-press OK on it |
| Add a channel without rebuilding | `adb shell am start -a android.intent.action.VIEW -d "pbblivetv://add?url=https://…/live.m3u8&title=Main" com.pbb.streamtv` |
| Deep link straight to a stream | `pbblivetv://play?url=…&title=Main` |

"Open website…" in the panel launches the site in the system browser — deliberately outside the
app, so no remote HTML ever touches a WebView.

## Deliberately not included

* **No WebView at all.** A remote site cannot be reliably focused with a remote, and wrapping it
  is what makes the current APK unusable on TV.
* **No ads / IAP / push SDKs.** They were disabled in the old config anyway and cost ~7 MB.
* **No page-scraping autoplay.** Point `MANIFEST` at streams you are allowed to rebroadcast; the
  player does not try to defeat a site's player.

⚠️ Content rights: "PBB" is ABS-CBN's. Ship this only with streams you own or are licensed to
rebroadcast, otherwise expect a Play Store takedown regardless of how good the player is.

## CI

`docs/ci/build-tv-apk.yml` is a ready GitHub Actions workflow. The GitHub App this session uses
has no `workflows` permission, so it could not be committed under `.github/workflows/`; enable it
with either:

* GitHub web UI → **Add file → Create new file** → `.github/workflows/build-tv-apk.yml`, paste the
  file contents, commit; or
* `gh` with a token that has the `workflow` scope:
  `gh api -X PUT repos/ngleaks/apk/contents/.github/workflows/build-tv-apk.yml -f message="ci: build TV APK" -f content="$(base64 -w0 docs/ci/build-tv-apk.yml)"`

After that every push builds `pbb-tv-apk` as a downloadable artifact.
