package com.pbb.livetv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.pbb.livetv.core.AppState;
import com.pbb.livetv.core.EngineFactory;
import com.pbb.livetv.media.PlayerEngine;
import com.pbb.livetv.ui.AllScreen;
import com.pbb.livetv.ui.CameraScreen;
import com.pbb.livetv.ui.NavBar;
import com.pbb.livetv.ui.PlayerControls;
import com.pbb.livetv.ui.PlayerUi;
import com.pbb.livetv.ui.Theme;
import com.pbb.livetv.ui.TileView;
import com.pbb.livetv.util.Net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only activity. Owns the players so no other component can leak them.
 *
 * <p>Structure: one content area that hosts either a {@link CameraScreen} (single camera section) or
 * an {@link AllScreen} (2x2 CCTV grid), plus a persistent {@link NavBar} and an on-demand
 * {@link PlayerControls} overlay. Each media engine is keyed by camera id, reused while its camera is
 * on screen and released when it leaves, which satisfies "no background playback, no duplicate
 * instances" without any static state.
 */
public final class MainActivity extends Activity implements PlayerEngine.Listener {

    static final String EXTRA_CAMERA = "camera";
    static final String ACTION_PLAY = "com.pbb.livetv.PLAY";
    static final String ACTION_RELOAD_CONFIG = "com.pbb.livetv.RELOAD_CONFIG";

    private static final long CONTROLS_TIMEOUT_MS = 6000L;
    private static final long EXIT_WINDOW_MS = 2200L;
    private static final long BACK_TO_LIVE_MS = 6000L;
    private static final long STATS_INTERVAL_MS = 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, PlayerEngine> engines = new LinkedHashMap<>();
    private final Map<String, Surface> surfaces = new HashMap<>();
    /** tile currently showing each camera, so surface callbacks reach the right view. */
    private final Map<String, TileView> tiles = new HashMap<>();
    /** camera id -> UI state, so a re-shown tile does not flash "connecting". */
    private final Map<String, PlayerUi.State> lastStates = new HashMap<>();
    private final Map<String, String> lastDetails = new HashMap<>();

    private AppState state;
    private EngineFactory engineFactory;

    private FrameLayout root;
    private FrameLayout contentHost;
    private NavBar navBar;
    private PlayerControls controls;
    private CameraScreen cameraScreen;
    private AllScreen allScreen;

    private Camera.Section section = Camera.Section.MAIN_LIVE;
    /** Camera shown fullscreen from the ALL grid; null means "grid is visible". */
    private Camera fullscreenCamera;
    private Camera activeCamera;

    private AudioManager audioManager;
    private boolean audioMuted = false;
    private long lastBackAt = 0L;
    private long lastBackToLiveAt = 0L;
    private boolean gridMutedNoticeShown = false;

    private final Runnable hideControls = () -> {
        if (controls != null && controls.isShown()) {
            controls.hide();
            focusContent();
        }
    };

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            updateStatusLine();
            ui.postDelayed(this, STATS_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        engineFactory = new EngineFactory();
        state = new AppState(this);
        section = state.section();

        buildUi();
        applySection(section, false);

        // Optional remote configuration override (off by default, never fetched unless configured).
        if (!android.text.TextUtils.isEmpty(CameraCatalog.REMOTE_CONFIG_URL)) {
            CameraCatalog.fetchAndMerge(this, CameraCatalog.REMOTE_CONFIG_URL, ui,
                    (cameras, error) -> {
                        if (cameras != null) {
                            state.replaceCatalog(cameras);
                            applySection(section, false);
                        }
                    });
        }
    }

    // ------------------------------------------------------------------ UI scaffolding

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);

        contentHost = new FrameLayout(this);
        root.addView(contentHost, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        cameraScreen = new CameraScreen(this, section);
        cameraScreen.setHost(new CameraScreen.Host() {
            @Override
            public void onRetry() {
                retryActive();
            }

            @Override
            public void onSelectCamera(Camera camera) {
                if (camera != null) {
                    playCamera(camera, false);
                }
            }

            @Override
            public void onFocusContent() {
                if (navBar.hasFocus()) {
                    navBar.setDimmed(true);
                }
            }
        });
        contentHost.addView(cameraScreen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        allScreen = new AllScreen(this);
        allScreen.setHost(new AllScreen.Host() {
            @Override
            public void onOpenCamera(Camera camera) {
                openFullscreen(camera);
            }

            @Override
            public void onFocusContent() {
                if (navBar.hasFocus()) {
                    navBar.setDimmed(true);
                }
            }
        });
        contentHost.addView(allScreen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        navBar = new NavBar(this);
        navBar.setListener(new NavBar.Listener() {
            @Override
            public void onSectionSelected(Camera.Section selected) {
                applySection(selected, true);
            }

            @Override
            public void onFocusChanged(boolean focused) {
                navBar.setDimmed(!focused);
            }
        });
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                NavBar.heightPx(this), android.view.Gravity.BOTTOM);
        root.addView(navBar, navLp);

        controls = new PlayerControls(this);
        controls.setListener(new PlayerControls.Listener() {
            @Override
            public void onPlayPause() {
                togglePlayPause();
            }

            @Override
            public void onRetry() {
                retryActive();
            }

            @Override
            public void onAudioToggle() {
                toggleMute();
            }

            @Override
            public void onClose() {
                closeControls();
            }

            @Override
            public void onNextCamera() {
                playNextCamera();
            }
        });
        root.addView(controls, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        navBar.setSelected(section);
        contentHost.setPadding(0, 0, 0, NavBar.heightPx(this));
    }

    // ------------------------------------------------------------------ sections

    private void applySection(Camera.Section newSection, boolean moveFocus) {
        boolean sectionChanged = newSection != section;
        section = newSection;
        fullscreenCamera = null;
        state.selectSection(newSection);
        navBar.setSelected(newSection);
        controls.hide();

        cameraScreen.setSection(newSection);
        boolean isAll = newSection == Camera.Section.ALL;
        cameraScreen.setVisibility(isAll ? View.GONE : View.VISIBLE);
        allScreen.setVisibility(isAll ? View.VISIBLE : View.GONE);

        if (isAll) {
            releaseAllEngines();
            List<Camera> cameras = state.camerasIn(Camera.Section.ALL);
            Camera remembered = state.selected(Camera.Section.ALL);
            allScreen.bind(cameras, remembered);
            activeCamera = remembered;
            if (moveFocus || sectionChanged) {
                allScreen.requestContentFocus();
            }
            startVisibleGridStreams();
            toastOnce("Grid preview muted · OK opens a camera with sound");
        } else {
            List<Camera> cameras = state.camerasIn(newSection);
            Camera camera = Camera.firstPlayable(cameras);
            if (camera == null && !cameras.isEmpty()) {
                camera = cameras.get(0);
            }
            cameraScreen.bind(cameras, camera);
            activeCamera = camera;
            if (camera != null) {
                playCamera(camera, moveFocus);
            } else {
                cameraScreen.tile().showState(PlayerUi.State.NO_STREAM, "");
            }
            if (moveFocus || sectionChanged) {
                cameraScreen.requestContentFocus();
            }
        }
        ui.removeCallbacks(statsTick);
        ui.post(statsTick);
    }

    /** Bind a camera to the single-camera screen and start it. */
    private void playCamera(Camera camera, boolean focusTile) {
        if (camera == null) {
            return;
        }
        List<Camera> cameras = state.camerasIn(section);
        cameraScreen.bind(cameras, camera);
        activeCamera = camera;
        state.rememberSelection(section, camera);
        if (focusTile) {
            cameraScreen.requestContentFocus();
        }
        if (!camera.playable()) {
            cameraScreen.tile().showState(camera.awaitingKey()
                    ? PlayerUi.State.LICENCE_REQUIRED : PlayerUi.State.NO_STREAM, "");
            return;
        }
        PlayerUi.State known = lastStates.get(camera.id);
        if (known != null) {
            cameraScreen.tile().showState(known, lastDetails.get(camera.id));
        } else {
            cameraScreen.tile().showState(PlayerUi.State.CONNECTING, "");
        }
        PlayerEngine engine = ensureEngine(camera);
        if (engine == null) {
            return;
        }
        attachTileSurface(cameraScreen.tile(), camera, engine);
        engine.setVolume(audioMuted ? 0f : 1f);
        if (engine.state() == PlayerEngine.State.IDLE) {
            engine.prepare(camera.url);
        } else if (engine.state() == PlayerEngine.State.PAUSED) {
            engine.play();
        }
        // Release engines that are no longer on screen in this section.
        List<String> keep = new ArrayList<>();
        keep.add(camera.id);
        releaseEnginesExcept(keep);
        updateStatusLine();
    }

    private void openFullscreen(Camera camera) {
        if (camera == null) {
            return;
        }
        if (!camera.playable()) {
            toast(camera.awaitingKey()
                    ? camera.label + ": ClearKey licence not provided"
                    : camera.label + ": no stream configured");
            return;
        }
        fullscreenCamera = camera;
        allScreen.setVisibility(View.GONE);
        cameraScreen.setVisibility(View.VISIBLE);
        state.rememberSelection(Camera.Section.ALL, camera);
        playCamera(camera, true);
        toast(camera.label + " · BACK returns to ALL");
    }

    /** Page the CCTV wall. Only used when focus is at the left/right edge of the grid. */
    private boolean changeGridPage(int delta) {
        int current = allScreen.page();
        int target = current + delta;
        if (target < 0 || target >= allScreen.pageCount()) {
            return false;
        }
        allScreen.setPage(target, state.selected(Camera.Section.ALL));
        startVisibleGridStreams();
        toast("Cameras " + (target * AllScreen.PER_PAGE + 1) + "–"
                + Math.min(state.camerasIn(Camera.Section.ALL).size(),
                (target + 1) * AllScreen.PER_PAGE));
        return true;
    }

    private void exitFullscreen() {
        if (fullscreenCamera == null) {
            return;
        }
        fullscreenCamera = null;
        releaseAllEngines();
        cameraScreen.setVisibility(View.GONE);
        allScreen.setVisibility(View.VISIBLE);
        allScreen.bind(state.camerasIn(Camera.Section.ALL), state.selected(Camera.Section.ALL));
        allScreen.requestContentFocus();
        startVisibleGridStreams();
        ui.post(statsTick);
    }

    // ------------------------------------------------------------------ engines

    private PlayerEngine ensureEngine(Camera camera) {
        PlayerEngine existing = engines.get(camera.id);
        if (existing != null) {
            return existing;
        }
        PlayerEngine engine = engineFactory.create(camera.url, this);
        engines.put(camera.id, engine);
        return engine;
    }

    private void attachTileSurface(TileView tile, Camera camera, PlayerEngine engine) {
        bindSurfaceCallbacks(tile, camera);
        Surface existing = surfaces.get(camera.id);
        boolean sameCameraOnTile = tile.camera() != null && camera.id.equals(tile.camera().id);
        if (existing != null && existing.isValid() && tile.hasVideo() && sameCameraOnTile) {
            engine.setSurface(existing);
            return;
        }
        tile.invalidateVideo();
        Surface surface = tile.attachVideo(false);
        if (surface != null) {
            surfaces.put(camera.id, surface);
            engine.setSurface(surface);
        }
    }

    private void bindSurfaceCallbacks(TileView tile, Camera camera) {
        tiles.put(camera.id, tile);
        tile.setSurfaceCallback(new TileView.SurfaceCallback() {
            @Override
            public void onSurface(TileView view, Surface surface) {
                surfaces.put(camera.id, surface);
                PlayerEngine bound = engines.get(camera.id);
                if (bound != null) {
                    bound.setSurface(surface);
                }
            }

            @Override
            public void onSurfaceLost(TileView view) {
                PlayerEngine bound = engines.get(camera.id);
                if (bound != null) {
                    bound.setSurface(null);
                }
                surfaces.remove(camera.id);
            }
        });
    }

    /**
     * Start the tiles on the current ALL page. Muted, at most {@link AllScreen#PER_PAGE} streams
     * (four). If the device cannot keep four decoders alive the tile shows its state instead of
     * pretending; nothing here fakes video.
     */
    private void startVisibleGridStreams() {
        List<String> keep = new ArrayList<>();
        for (TileView tile : allScreen.visibleTiles()) {
            Camera camera = tile.camera();
            if (camera == null) {
                continue;
            }
            if (!camera.playable()) {
                tile.showState(camera.awaitingKey()
                        ? PlayerUi.State.LICENCE_REQUIRED : PlayerUi.State.NO_STREAM, "");
                continue;
            }
            keep.add(camera.id);
            PlayerEngine engine = ensureEngine(camera);
            engine.setVolume(0f);
            attachGridSurface(tile, camera, engine);
            PlayerUi.State known = lastStates.get(camera.id);
            tile.showState(known != null ? known : PlayerUi.State.CONNECTING, lastDetails.get(camera.id));
            if (engine.state() == PlayerEngine.State.IDLE) {
                engine.prepare(camera.url);
            } else if (engine.state() == PlayerEngine.State.PAUSED) {
                engine.play();
            }
        }
        releaseEnginesExcept(keep);
    }

    private void attachGridSurface(TileView tile, Camera camera, PlayerEngine engine) {
        bindSurfaceCallbacks(tile, camera);
        if (tile.hasVideo()) {
            Surface existing = surfaces.get(camera.id);
            if (existing != null && existing.isValid()) {
                engine.setSurface(existing);
            }
            return;
        }
        Surface surface = tile.attachVideo(false);
        if (surface != null) {
            surfaces.put(camera.id, surface);
            engine.setSurface(surface);
        }
    }

    /** Section the user has selected (also correct while ALL is showing a fullscreen camera). */
    public Camera.Section currentSection() {
        return section;
    }

    public String currentCameraId() {
        if (section == Camera.Section.ALL && fullscreenCamera == null) {
            return null;
        }
        return activeCamera == null ? null : activeCamera.id;
    }

    private void releaseEnginesExcept(List<String> keepIds) {
        List<String> remove = new ArrayList<>();
        for (String id : engines.keySet()) {
            if (!keepIds.contains(id)) {
                remove.add(id);
            }
        }
        for (String id : remove) {
            PlayerEngine engine = engines.remove(id);
            if (engine != null) {
                engine.release();
            }
            Surface surface = surfaces.remove(id);
            if (surface != null) {
                surface.release();
            }
            tiles.remove(id);
        }
    }

    private void releaseAllEngines() {
        releaseEnginesExcept(new ArrayList<>());
        for (Camera camera : state.cameras()) {
            lastStates.remove(camera.id);
            lastDetails.remove(camera.id);
        }
    }

    // ------------------------------------------------------------------ engine callbacks

    @Override
    public void onEngineState(PlayerEngine engine, PlayerEngine.State engineState, String detail) {
        Camera camera = cameraFor(engine);
        if (camera == null) {
            return;
        }
        PlayerUi.State uiState = PlayerUi.fromEngine(engineState);
        lastStates.put(camera.id, uiState);
        lastDetails.put(camera.id, detail);
        if (uiState == PlayerUi.State.PLAYING) {
            lastDetails.remove(camera.id);
        }

        if (fullscreenCamera != null && camera.id.equals(fullscreenCamera.id)) {
            cameraScreen.tile().showState(uiState, detail);
        } else if (section != Camera.Section.ALL && activeCamera != null
                && camera.id.equals(activeCamera.id)) {
            cameraScreen.tile().showState(uiState, detail);
        } else {
            TileView gridTile = allScreen.tileFor(camera.id);
            if (gridTile != null) {
                gridTile.showState(uiState, detail);
            }
        }
        updateStatusLine();
    }

    @Override
    public void onVideoSize(PlayerEngine engine, int width, int height) {
        // The 16:9 stage centres the tile; a real player would also copy the aspect ratio here.
    }

    private Camera cameraFor(PlayerEngine engine) {
        for (Map.Entry<String, PlayerEngine> entry : engines.entrySet()) {
            if (entry.getValue() == engine) {
                return state.byId(entry.getKey());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ player actions

    private void togglePlayPause() {
        Camera camera = visibleCamera();
        if (camera == null) {
            return;
        }
        PlayerEngine engine = engines.get(camera.id);
        if (engine == null) {
            playCamera(camera, false);
            return;
        }
        PlayerEngine.State s = engine.state();
        if (s == PlayerEngine.State.ERROR || s == PlayerEngine.State.IDLE) {
            engine.retry();
        } else if (engine.isPlaying()) {
            engine.pause();
        } else {
            engine.play();
        }
        showControls();
    }

    private void retryActive() {
        Camera camera = visibleCamera();
        if (camera == null) {
            return;
        }
        if (!Net.isOnline(this)) {
            toast("No network connection");
        }
        PlayerEngine engine = ensureEngine(camera);
        engine.retry();
        lastStates.remove(camera.id);
        lastDetails.remove(camera.id);
        if (fullscreenCamera != null || section != Camera.Section.ALL) {
            cameraScreen.tile().showState(PlayerUi.State.CONNECTING, "");
        } else {
            TileView tile = allScreen.tileFor(camera.id);
            if (tile != null) {
                tile.showState(PlayerUi.State.CONNECTING, "");
            }
        }
        showControls();
    }

    private void toggleMute() {
        audioMuted = !audioMuted;
        applyVolume();
        showControls();
    }

    private void applyVolume() {
        for (Map.Entry<String, PlayerEngine> entry : engines.entrySet()) {
            Camera camera = state.byId(entry.getKey());
            boolean audible = !audioMuted && camera != null && camera == visibleCamera();
            entry.getValue().setVolume(audible ? 1f : 0f);
        }
    }

    private void playNextCamera() {
        List<Camera> pool = state.camerasIn(section);
        if (pool.isEmpty()) {
            return;
        }
        int index = pool.indexOf(activeCamera);
        Camera next = pool.get((index + 1) % pool.size());
        playCamera(next, true);
        closeControls();
    }

    private Camera visibleCamera() {
        if (section == Camera.Section.ALL && fullscreenCamera == null) {
            return allScreen.cameraAt(allScreen.focusedSlot());
        }
        return activeCamera;
    }

    private boolean isGridVisible() {
        return section == Camera.Section.ALL && fullscreenCamera == null;
    }

    // ------------------------------------------------------------------ controls overlay

    private void showControls() {
        if (isGridVisible()) {
            return;
        }
        Camera camera = visibleCamera();
        if (camera == null) {
            return;
        }
        PlayerEngine engine = engines.get(camera.id);
        PlayerEngine.State s = engine == null ? PlayerEngine.State.IDLE : engine.state();
        boolean playing = engine != null && engine.isPlaying();
        String[] labels;
        boolean[] enabled;
        boolean playable = camera.playable();
        if (!playable) {
            labels = new String[]{"Next camera", "Close"};
            enabled = new boolean[]{true, true};
        } else if (s == PlayerEngine.State.ERROR) {
            labels = new String[]{"Retry", "Next camera", audioMuted ? "Sound on" : "Mute", "Close"};
            enabled = new boolean[]{true, true, true, true};
        } else {
            labels = new String[]{playing ? "Pause" : "Play", "Next camera",
                    audioMuted ? "Sound on" : "Mute", "Close"};
            enabled = new boolean[]{true, true, true, true};
        }
        String status = camera.footerText() + "   ·   "
                + PlayerUi.label(lastStates.containsKey(camera.id) ? lastStates.get(camera.id)
                : PlayerUi.State.CONNECTING);
        controls.configure(camera.label, status, labels, enabled);
        controls.showAndFocus();
        ui.removeCallbacks(hideControls);
        ui.postDelayed(hideControls, CONTROLS_TIMEOUT_MS);
    }

    private void closeControls() {
        ui.removeCallbacks(hideControls);
        controls.hide();
        focusContent();
    }

    private void focusContent() {
        if (isGridVisible()) {
            allScreen.requestContentFocus();
        } else {
            cameraScreen.requestContentFocus();
        }
    }

    private void updateStatusLine() {
        if (!controls.isShown()) {
            return;
        }
        Camera camera = visibleCamera();
        if (camera == null) {
            return;
        }
        PlayerUi.State uiState = lastStates.get(camera.id);
        controls.configure(camera.label,
                camera.footerText() + "   ·   " + PlayerUi.label(uiState == null
                        ? PlayerUi.State.CONNECTING : uiState),
                currentControlLabels(), currentControlEnabled());
    }

    private String[] currentControlLabels() {
        Camera camera = visibleCamera();
        if (camera == null || !camera.playable()) {
            return new String[]{"Next camera", "Close"};
        }
        PlayerEngine engine = engines.get(camera.id);
        PlayerEngine.State s = engine == null ? PlayerEngine.State.IDLE : engine.state();
        if (s == PlayerEngine.State.ERROR) {
            return new String[]{"Retry", "Next camera", audioMuted ? "Sound on" : "Mute", "Close"};
        }
        boolean playing = engine != null && engine.isPlaying();
        return new String[]{playing ? "Pause" : "Play", "Next camera",
                audioMuted ? "Sound on" : "Mute", "Close"};
    }

    private boolean[] currentControlEnabled() {
        Camera camera = visibleCamera();
        if (camera == null || !camera.playable()) {
            return new boolean[]{true, true};
        }
        PlayerEngine engine = engines.get(camera.id);
        PlayerEngine.State s = engine == null ? PlayerEngine.State.IDLE : engine.state();
        if (s == PlayerEngine.State.ERROR) {
            return new boolean[]{true, true, true, true};
        }
        return new boolean[]{true, true, true, true};
    }

    // ------------------------------------------------------------------ key handling

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return handleBack();
        }
        if (controls.isShown()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    controls.moveFocus(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    controls.moveFocus(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    closeControls();
                    navBar.focusSection(section);
                    navBar.setDimmed(false);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    togglePlayPause();
                    return true;
                default:
                    return true; // swallow other keys while the overlay has focus
            }
        }

        if (navBar.hasFocus()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: {
                    Camera.Section target = Camera.Section.fromIndex(
                            Math.max(0, section.index - 1));
                    navBar.focusSection(target);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    Camera.Section target = Camera.Section.fromIndex(
                            Math.min(Camera.Section.values().length - 1, section.index + 1));
                    navBar.focusSection(target);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_UP:
                    focusContent();
                    return true;
                default:
                    return super.onKeyDown(keyCode, event);
            }
        }

        // Content has focus.
        if (isGridVisible()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (allScreen.moveFocus(keyCode)) {
                        return true;
                    }
                    return changeGridPage(-1);
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (allScreen.moveFocus(keyCode)) {
                        return true;
                    }
                    return changeGridPage(1);
                case KeyEvent.KEYCODE_DPAD_UP:
                    return allScreen.moveFocus(keyCode);
                case KeyEvent.KEYCODE_DPAD_DOWN: {
                    if (allScreen.moveFocus(KeyEvent.KEYCODE_DPAD_DOWN)) {
                        return true;
                    }
                    navBar.focusSection(section);
                    navBar.setDimmed(false);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: {
                    Camera camera = allScreen.cameraAt(allScreen.focusedSlot());
                    if (camera != null) {
                        openFullscreen(camera);
                    }
                    return true;
                }
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    return true;
                default:
                    return super.onKeyDown(keyCode, event);
            }
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                showControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                navBar.focusSection(section);
                navBar.setDimmed(false);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: {
                Camera.Section target = Camera.Section.fromIndex(Math.max(0, section.index - 1));
                if (target != section) {
                    applySection(target, false);
                    navBar.setSelected(target);
                    focusContent();
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                Camera.Section target = Camera.Section.fromIndex(Math.min(
                        Camera.Section.values().length - 1, section.index + 1));
                if (target != section) {
                    applySection(target, false);
                    navBar.setSelected(target);
                    focusContent();
                }
                return true;
            }
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                togglePlayPause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                closeControls();
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return super.onKeyDown(keyCode, event);
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private boolean handleBack() {
        if (controls.isShown()) {
            closeControls();
            return true;
        }
        if (section == Camera.Section.ALL && fullscreenCamera != null) {
            exitFullscreen();
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackToLiveAt > BACK_TO_LIVE_MS && section != Camera.Section.MAIN_LIVE) {
            lastBackToLiveAt = now;
            applySection(Camera.Section.MAIN_LIVE, true);
            navBar.setSelected(Camera.Section.MAIN_LIVE);
            toast("Back to MAIN LIVE · press BACK again to exit");
            return true;
        }
        if (now - lastBackAt < EXIT_WINDOW_MS) {
            finish();
            return true;
        }
        lastBackAt = now;
        toast("Press BACK again to exit");
        return true;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onPause() {
        super.onPause();
        // Never play audio in the background: pause every engine, keep warm state for instant resume.
        for (PlayerEngine engine : engines.values()) {
            engine.pause();
        }
        ui.removeCallbacks(statsTick);
        ui.removeCallbacks(hideControls);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (section == Camera.Section.ALL && fullscreenCamera == null) {
            startVisibleGridStreams();
        } else if (activeCamera != null) {
            PlayerEngine engine = engines.get(activeCamera.id);
            if (engine != null) {
                engine.setVolume(audioMuted ? 0f : 1f);
                engine.play();
            }
        }
        ui.removeCallbacks(statsTick);
        ui.post(statsTick);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        releaseAllEngines();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String cameraId = intent.getStringExtra(EXTRA_CAMERA);
        if (cameraId != null) {
            Camera camera = state.byId(cameraId);
            if (camera != null) {
                applySection(camera.section, true);
                navBar.setSelected(camera.section);
                if (camera.section == Camera.Section.ALL) {
                    openFullscreen(camera);
                } else {
                    playCamera(camera, true);
                }
            }
        }
        Uri data = intent.getData();
        if (data != null && CameraCatalog.SCHEME.equals(data.getScheme())) {
            String url = data.getQueryParameter("url");
            String label = data.getQueryParameter("label");
            String sectionName = data.getQueryParameter("section");
            if (url != null && !url.isEmpty()) {
                Camera.Section target = Camera.Section.fromName(sectionName, Camera.Section.ALL);
                String id = "custom-" + label;
                Camera camera = new Camera(id, label, "Custom stream", target, url, "", "", "", 99);
                List<Camera> merged = new ArrayList<>(state.cameras());
                Camera existing = Camera.byId(merged, id);
                if (existing != null) {
                    merged.set(merged.indexOf(existing), camera);
                } else {
                    merged.add(camera);
                }
                CameraCatalog.persist(this, merged);
                state.replaceCatalog(merged);
                applySection(target, true);
                navBar.setSelected(target);
                toast("Added " + camera.label);
            }
        }
        if (ACTION_RELOAD_CONFIG.equals(intent.getAction()) && !android.text.TextUtils.isEmpty(
                CameraCatalog.REMOTE_CONFIG_URL)) {
            CameraCatalog.fetchAndMerge(this, CameraCatalog.REMOTE_CONFIG_URL, ui,
                    (cameras, error) -> {
                        if (cameras != null) {
                            state.replaceCatalog(cameras);
                            applySection(section, false);
                            toast("Catalogue refreshed");
                        } else {
                            toast("Catalogue refresh failed: " + error);
                        }
                    });
        }
    }

    // ------------------------------------------------------------------ small helpers

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void toastOnce(String message) {
        if (gridMutedNoticeShown) {
            return;
        }
        gridMutedNoticeShown = true;
        toast(message);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
