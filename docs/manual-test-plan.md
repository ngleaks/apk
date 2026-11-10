# Manual test plan — Android TV / TV boxes

The build machine that produced the APKs in this repo has no Android runtime, so execution
happened only through static verification (compile, package, signature, resource badging).
Run this matrix on a real device or emulator before shipping:

**Device targets:** Android TV emulator (`adb devices`) — 720p (tv_720p), 1080p, 4K; or a TV box.
Launcher must show “PBB LIVE” with the red eye banner (LEANBACK_LAUNCHER).

## Launch & navigation
1. Fresh install → app opens directly on **MAIN LIVE**; no onboarding; nav bar visible.
2. After 4 s without remote input, nav fades/slides out; video area is unobstructed.
3. D-pad **DOWN** anywhere → nav returns; focus is on the current section; LEFT/RIGHT move the
   four items; OK switches section; bar hides again after 4 s idle.
4. D-pad **UP** or **BACK** while the bar is focused → bar hides, focus returns to content.
   Auto-hide must not fire while a nav item has focus (verify: hold/select an item, wait 10 s).
5. BACK on a section screen → app exits (MAIN LIVE) or nothing crashes; BACK in fullscreen
   overlay → returns to the grid with the same tile focused (not section 1).

## MAIN LIVE with no configuration (shipping default)
6. Main Live shows CONNECTING → **NOT CONFIGURED** panel; demo button is focusable; OK opens
   the public bipbop demo fullscreen (or shows its own error + RETRY if the network blocks it).
7. Press BACK from the demo → returns to Main Live; bar hidden.

## Player states (use the demo stream or your authorized feed)
8. Connecting spinner → LIVE badge + title chrome; chrome auto-hides after 4 s; OK toggles
   chrome (and pause/resume); video keeps 16:9, letterboxed, never stretched.
9. Point the feed at a dead URL → **STREAM UNAVAILABLE** + message + RETRY; OK on RETRY
   re-attempts; no crash; error text contains no URLs.
10. Airplane mode mid-playback → buffering/stall → error state; recover after network returns.
11. Mute button in chrome (DPAD_UP from stage) toggles audio; icon flips.

## DINING / EXTERNAL
12. With no feeds configured → empty state; OK on “PLAY PUBLIC DEMO STREAM” opens fullscreen demo.
13. After configuring 1–2 feeds each → cards render (16:9 thumb, label, LIVE/DEMO badge);
    OK opens fullscreen; BACK returns to the list with focus on the same card.

## ALL grid
14. Two×two grid, page dots only when >4 ALL feeds; LEFT/RIGHT at the edge flips pages;
    focus remains in the same row/col after a page flip.
15. OK on a tile → fullscreen; BACK → grid restored; same tile focused; selection persisted
    across app restart (check `last_all_index`).
16. Four simultaneous feeds play (demo + three copies of the demo URL are fine for testing);
    focused tile is audible, others muted; on a low-RAM box, only the focused tile plays and
    others show “STANDBY / Focused tile plays”.
17. Long-running soak: leave the grid 30 min → memory stable, no leaked players
    (count MediaPlayer instances via `adb shell dumpsys media_player` if available).

## Lifecycle
18. HOME button → app backgrounds, all decoders stop (`media.player` dump empty); relaunch
    resumes cleanly on the last section.
19. Section switch Main → Dining → Back; players released (`adb shell dumpsys media.player` empty).
20. Screen-off/on; app survives; reconnects.

## Fault injection
21. `streams.json` with an invalid section value → falls back to ALL, app launches.
22. `streams.json` with an `http://` URL → catalog loads; player fails with a clear error
    (cleartext is blocked by default; use https).
23. `streams.json` override with garbage → built-in catalog used; a toast explains why.
24. Deep link: `pbblivetv://play?url=https%3A%2F%2Fexample.com%2Flive.m3u8&title=Test` opens overlay;
    malformed/missing `url` → ignored with a toast.

## Pass criteria
All 24 checks. Any crash or focus trap is a release blocker.
