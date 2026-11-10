# App architecture

One activity, no fragments, no ViewModel library, no third-party runtime code. Everything is
framework Java 8 so the app installs and stays small on cheap TV boxes (minSdk 24).

```
MainActivity
 ├── AppState            camera list + selected section + per-section selection memory
 │    └── CameraCatalog  assets/streams.json → SharedPreferences overrides → deep link / remote JSON
 ├── MainActivity.engines: LinkedHashMap<cameraId, PlayerEngine>   (the player pool)
 │    └── EngineFactory → HlsEngine | ProgressiveEngine   (both implement media/PlayerEngine)
 └── views
      ├── CameraScreen   single camera: header, camera strip, 16:9 stage (TileView)
      ├── AllScreen      2×2 paged CCTV wall (4 TileViews) + GridNavigator for D-pad maths
      ├── NavBar         4 sections; owns LEFT/RIGHT while focused
      └── PlayerControls auto-hiding OK overlay
```

## Why one activity

Section switching and fullscreen open/close are pure view swaps inside one activity, so:

* the **same** `PlayerEngine` survives a section change when its camera stays on screen (no re-buffer);
* BACK is a single, explicit state machine (`controls → fullscreen → MAIN LIVE → exit`) instead of
  activity-task behaviour that differs between TV vendors;
* nothing is duplicated in `onCreate`, so process death simply restarts at MAIN LIVE.

## Player lifecycle (the part that usually leaks)

* `ensureEngine(camera)` returns an existing engine or builds one via `EngineFactory`.
* After every section/grid change, `releaseEnginesExcept(keepIds)` releases engines whose camera is
  no longer visible — `MediaPlayer.release()`, io executor shutdown, surface released.
* Surface ownership is per camera (`MainActivity.surfaces`, `tiles`) and surface callbacks are bound
  to the **camera**, not to the view, so a `SurfaceView` recreated by the framework re-attaches to
  the live engine instead of dropping the frame.
* `onPause` pauses every engine (audio never continues in the background) and `onResume` resumes the
  visible one. `onDestroy` releases everything and clears the handler queue.

## Engine state machine

`PlayerEngine.State`: `IDLE → CONNECTING → PLAYING ⇄ PAUSED`, plus `BUFFERING` and `ERROR`.
`PlayerUi.State` adds the two catalogue-only states `NO_STREAM` and `LICENCE_REQUIRED`, which is what
lets a tile say something true instead of spinning.

`HlsEngine` details:

* loads the playlist off the main thread, follows master → variant (highest ≤ 1080p, smallest if all
  renditions are larger);
* starts two segments behind the live edge, then queues up to 5, prefetching when fewer than 3 remain;
* tracks `EXT-X-MEDIA-SEQUENCE` so a stall resumes at the live edge instead of replaying;
* refills by re-reading the media playlist (also refreshes rotated CDN tokens);
* a **generation counter** invalidated by `prepare()` stops a stale worker from injecting segments
  into a new stream;
* encrypted playlists (`EXT-X-KEY`) are reported as `ERROR` — never silently ignored.

`ProgressiveEngine` is the plain `MediaPlayer` path for MP4/WebM and any URL that is not HLS.

## Focus and key routing

`MainActivity.onKeyDown` decides by *mode*, not by focus search:

1. controls visible → controls mode (◀ ▶ between actions, ▼ closes to nav, BACK closes);
2. nav bar has focus → `NavBar.dispatchKeyEvent` owns ◀ ▶ (section change), ▲ returns to content;
3. ALL grid visible → `GridNavigator.next()` moves between tiles, OK opens fullscreen;
4. single camera → OK shows controls, ▼ to nav, ◀ ▶ change section.

That is why the remote cannot get "stuck": every mode has a documented exit, and the only focusables
are the four nav cells, the four tiles, the player surface and the control buttons.

## Threading

| Work | Thread |
|---|---|
| Playlist fetch + segment queue | `pbb-hls` single-thread executor per engine |
| `MediaPlayer` callbacks | main looper |
| UI state | main thread only; `PlayerEngine` state is `volatile` |

## Package map

| Package | Responsibility |
|---|---|
| `com.pbb.livetv` | `MainActivity`, `Camera`, `CameraCatalog`, `StreamFormat` |
| `…core` | `AppState`, `EngineFactory`, `GridNavigator` (pure) |
| `…media` | `PlayerEngine`, `HlsEngine`, `HlsPlaylist` (pure parser), `ProgressiveEngine` |
| `…ui` | `Theme`, `UiKit`, `NavBar`, `TileView`, `CameraScreen`, `AllScreen`, `PlayerControls`, `TileLayout` (pure), `PlayerUi` |
| `…util` | `Net` (connectivity + HTTP helpers) |

"pure" classes have no Android dependencies and are covered by `buildtools/test_core.sh`.
