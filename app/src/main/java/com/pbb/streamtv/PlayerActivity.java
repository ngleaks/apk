package com.pbb.streamtv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One full-screen, D-pad driven player.
 *
 * <p>Why not a WebView? A website cannot be focused with a TV remote and drags in desktop
 * assumptions. Here nothing remote is ever rendered: the channel list only holds stream URLs and
 * playback happens through {@link StreamEngine} (HLS segments or a progressive file), so the
 * hardware decoders, audio focus and the remote's media keys all behave like a TV app should.
 */
public final class PlayerActivity extends Activity implements StreamEngine.Listener {

    static final String EXTRA_URL = "extra_url";
    static final String EXTRA_TITLE = "extra_title";

    private static final long EXIT_WINDOW_MS = 2500L;
    private static final long CHROME_TIMEOUT_MS = 4500L;
    private static final long[] RETRY_DELAYS_MS = {1_500L, 3_000L, 6_000L, 12_000L, 20_000L};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable retryRunnable = this::retryNow;
    private final Runnable hideChrome = () -> setChromeVisible(false);

    private ExecutorService io;
    private StreamEngine engine;
    private SurfaceHost surfaceHost;
    private OverlayView overlay;
    private LinearLayout panel;
    private LinearLayout channelList;
    private ScrollView panelScroll;

    private AudioManager audioManager;
    private boolean noisyRegistered = false;

    private String currentUrl = "";
    private String currentTitle = "";
    private int retryIndex = 0;
    private long lastBackAt = 0L;
    private boolean chromeVisible = true;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pbb-stream");
            t.setDaemon(true);
            return t;
        });

        buildUi();
        ApiCompat.goImmersive(getWindow().getDecorView());
        handleIntent(getIntent(), true);
    }

    // ------------------------------------------------------------------ UI construction

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceHost = new SurfaceHost(this);
        surfaceHost.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surfaceHost.setListener(new SurfaceHost.Listener() {
            @Override
            public void onSurfaceReady(android.view.Surface surface) {
                if (engine != null) {
                    engine.setSurface(surface);
                }
            }

            @Override
            public void onSurfaceGone() {
                if (engine != null) {
                    engine.setSurface(null);
                }
            }
        });
        root.addView(surfaceHost);

        overlay = new OverlayView(this);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setHint("OK  ·  play/pause      ←  ·  channels      BACK  ·  exit");
        overlay.setLogo(BitmapFactory.decodeResource(getResources(), R.drawable.splash_logo));
        overlay.setShowLogo(true);
        root.addView(overlay);

        panel = buildPanel();
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 340), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        panel.setLayoutParams(panelLp);
        panel.setVisibility(View.GONE);
        root.addView(panel);

        setContentView(root);
    }

    private LinearLayout buildPanel() {
        LinearLayout panelRoot = new LinearLayout(this);
        panelRoot.setOrientation(LinearLayout.VERTICAL);
        panelRoot.setBackgroundColor(Ui.PANEL_BG);
        panelRoot.setPadding(Ui.dp(this, 22), Ui.dp(this, 26), Ui.dp(this, 22), Ui.dp(this, 18));
        panelRoot.setFocusable(false);

        panelScroll = new ScrollView(this);
        panelScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        panelScroll.setVerticalScrollBarEnabled(false);

        channelList = new LinearLayout(this);
        channelList.setOrientation(LinearLayout.VERTICAL);
        panelScroll.addView(channelList);
        panelRoot.addView(panelScroll);

        TextView footer = Ui.label(this,
                "ADD A CHANNEL FROM YOUR PC\n"
                        + "adb shell am start -a android.intent.action.VIEW \\\n"
                        + "  -d \"pbblivetv://add?url=https://…/live.m3u8&title=Main\" \\\n"
                        + "  com.pbb.streamtv",
                10f, Ui.TEXT_MUTED, false);
        footer.setLineSpacing(Ui.dp(this, 1), 1f);
        footer.setPadding(0, Ui.dp(this, 10), 0, 0);
        panelRoot.addView(footer);

        return panelRoot;
    }

    private void rebuildChannelButtons(String focusUrl) {
        channelList.removeAllViews();

        TextView header = Ui.label(this, "CHANNELS", 13f, 0xFF9FB0FF, false);
        header.setLetterSpacing(0.18f);
        header.setPadding(0, 0, 0, Ui.dp(this, 14));
        channelList.addView(header);

        List<ChannelStore.Channel> channels = ChannelStore.load(this);
        if (channels.isEmpty()) {
            TextView empty = Ui.label(this,
                    "No channels yet.\n\nAdd one with the adb command below, or fill in StreamConfig.MANIFEST and rebuild.",
                    12f, 0xFFCCD3EA, false);
            empty.setLineSpacing(Ui.dp(this, 2), 1f);
            empty.setPadding(0, 0, 0, Ui.dp(this, 12));
            channelList.addView(empty);
        }

        Button first = null;
        for (final ChannelStore.Channel channel : channels) {
            Button b = Ui.button(this, channel.title);
            b.setOnClickListener(v -> {
                setPanelVisible(false);
                play(channel.url, channel.title);
            });
            b.setOnLongClickListener(v -> {
                ChannelStore.remove(this, channel.url);
                rebuildChannelButtons(null);
                Toast.makeText(this, "Removed " + channel.title, Toast.LENGTH_SHORT).show();
                return true;
            });
            channelList.addView(b);
            if (first == null) {
                first = b;
            }
            if (focusUrl != null && channel.url.equals(focusUrl)) {
                first = b;
            }
        }

        Button site = Ui.button(this, "Open website…");
        site.setOnClickListener(v -> {
            setPanelVisible(false);
            openExternal(StreamConfig.LANDING_PAGE);
        });
        channelList.addView(site);

        Button close = Ui.button(this, "Close");
        close.setOnClickListener(v -> setPanelVisible(false));
        channelList.addView(close);

        if (first != null) {
            first.requestFocus();
        }
    }

    private void setPanelVisible(boolean visible) {
        panel.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            rebuildChannelButtons(currentUrl.isEmpty() ? null : currentUrl);
            ui.removeCallbacks(hideChrome);
        } else {
            surfaceHost.requestFocus();
        }
    }

    private void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        overlay.fadeUi(visible);
        ui.removeCallbacks(hideChrome);
        if (visible && panel.getVisibility() != View.VISIBLE) {
            ui.postDelayed(hideChrome, CHROME_TIMEOUT_MS);
        }
    }

    private void pokeChrome() {
        setChromeVisible(true);
        updateProgress();
    }

    // ------------------------------------------------------------------ playback

    private void play(String url, String title) {
        if (url == null || url.isEmpty()) {
            return;
        }
        ui.removeCallbacks(retryRunnable);
        if (engine != null) {
            engine.release();
        }
        currentUrl = url;
        currentTitle = StreamConfig.titleFor(url, title);
        retryIndex = 0;

        engine = HlsPlaylist.looksLikePlaylist(url) || isLikelyHls(url)
                ? new SegmentPlayer(this, io)
                : new FilePlayer(this);
        overlay.setCentreMessage("Connecting…\n" + currentTitle, 2);
        overlay.setTitle(currentTitle, "", engine.isLive());
        surfaceHost.requestFocus();
        engine.prepare(url);
        StreamConfig.remember(getSharedPreferences(StreamConfig.PREFS, MODE_PRIVATE), url, currentTitle);
    }

    /** Some CDNs serve HLS from a query string instead of a .m3u8 path. */
    private static boolean isLikelyHls(String url) {
        String lower = url.toLowerCase();
        return lower.contains("format=m3u8") || lower.contains("/hls") || lower.contains("playlist");
    }

    private void retryNow() {
        if (currentUrl.isEmpty() || engine == null) {
            return;
        }
        overlay.setCentreMessage("Reconnecting…\n" + currentTitle, 2);
        engine.prepare(currentUrl);
    }

    private void scheduleRetry(String detail) {
        long delay = RETRY_DELAYS_MS[Math.min(retryIndex, RETRY_DELAYS_MS.length - 1)];
        retryIndex++;
        overlay.setCentreMessage("Stream unreachable"
                + (detail.isEmpty() ? "" : "\n" + detail) + "\nretrying in " + Math.max(1, delay / 1000) + "s"
                + "\n\npress ← for channels", 4);
        ui.removeCallbacks(retryRunnable);
        ui.postDelayed(retryRunnable, delay);
    }

    @Override
    public void onState(StreamEngine.State state, String detail) {
        switch (state) {
            case BUFFERING:
                overlay.setCentreMessage("Buffering…", 1);
                setChromeVisible(true);
                break;
            case PLAYING:
                retryIndex = 0;
                overlay.setShowLogo(false);
                overlay.setCentreMessage("", 0);
                overlay.setTitle(currentTitle, "", engine != null && engine.isLive());
                setChromeVisible(true);
                break;
            case PAUSED:
                overlay.setCentreMessage("Paused", 1);
                setChromeVisible(true);
                break;
            case ENDED:
                overlay.setCentreMessage("End of stream\n\npress ← for channels", 3);
                setChromeVisible(true);
                break;
            case ERROR:
                scheduleRetry(detail == null ? "" : detail);
                break;
            default:
                break;
        }
        updateProgress();
    }

    private void updateProgress() {
        if (engine == null) {
            return;
        }
        boolean seekable = !engine.isLive() && engine.durationMs() > 0L;
        overlay.setProgress(engine.positionMs(), engine.durationMs(), seekable);
    }

    private void togglePlayPause() {
        if (engine == null) {
            retryNow();
            return;
        }
        if (engine.isPlaying()) {
            pause();
        } else {
            engine.play();
        }
        pokeChrome();
    }

    private void pause() {
        if (engine != null) {
            engine.pause();
        }
    }

    private void seekBy(long deltaMs) {
        if (engine == null || engine.isLive()) {
            Toast.makeText(this, "Live stream · cannot seek", Toast.LENGTH_SHORT).show();
            return;
        }
        engine.seekTo(engine.positionMs() + deltaMs);
        pokeChrome();
    }

    private void changeVolume(int direction) {
        if (audioManager == null) {
            return;
        }
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        pokeChrome();
    }

    // ------------------------------------------------------------------ intents

    private void handleIntent(Intent intent, boolean firstLaunch) {
        if (intent == null) {
            showStartup();
            return;
        }
        Uri data = intent.getData();
        if (data != null && StreamConfig.DEEPLINK_SCHEME.equals(data.getScheme())) {
            String host = data.getHost() == null ? "" : data.getHost();
            String url = data.getQueryParameter("url");
            String title = data.getQueryParameter("title");
            if ("add".equals(host) && url != null) {
                ChannelStore.upsert(this, title, url);
                Toast.makeText(this, "Channel added", Toast.LENGTH_SHORT).show();
                if (firstLaunch) {
                    showStartup();
                } else if (panel.getVisibility() == View.VISIBLE) {
                    rebuildChannelButtons(url);
                }
                return;
            }
            if (url != null && !url.isEmpty()) {
                play(url, title);
                return;
            }
        }
        String extra = intent.getStringExtra(EXTRA_URL);
        if (extra != null && !extra.isEmpty()) {
            play(extra, intent.getStringExtra(EXTRA_TITLE));
            return;
        }
        StreamConfig.PlayRequest resume = StreamConfig.resolve(null,
                getSharedPreferences(StreamConfig.PREFS, MODE_PRIVATE));
        if (!resume.isEmpty() && StreamConfig.isAutoResume()) {
            play(resume.url, resume.title);
        } else {
            showStartup();
        }
    }

    private void showStartup() {
        overlay.setCentreMessage("", 0);
        overlay.setTitle("PBBforFREE", "", true);
        setPanelVisible(true);
        setChromeVisible(true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, false);
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, "No browser on this device", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ key handling

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (panel.getVisibility() != View.VISIBLE) {
                    setPanelVisible(true);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (panel.getVisibility() != View.VISIBLE) {
                    seekBy(10_000L);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                setPanelVisible(panel.getVisibility() != View.VISIBLE);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayPause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (engine != null) {
                    engine.play();
                }
                pokeChrome();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                pause();
                pokeChrome();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (engine != null) {
                    engine.stop();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(30_000L);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-30_000L);
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
                changeVolume(AudioManager.ADJUST_RAISE);
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                changeVolume(AudioManager.ADJUST_LOWER);
                return true;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                changeVolume(AudioManager.ADJUST_TOGGLE_MUTE);
                return true;
            case KeyEvent.KEYCODE_A:
                if (panel.getVisibility() != View.VISIBLE) {
                    changeVolume(AudioManager.ADJUST_RAISE);
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBack();
            return true;
        }
        if (panel.getVisibility() != View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (!chromeVisible) {
                    setChromeVisible(true);
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    togglePlayPause();
                }
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private void handleBack() {
        if (panel.getVisibility() == View.VISIBLE) {
            setPanelVisible(false);
            return;
        }
        if (chromeVisible) {
            setChromeVisible(false);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackAt < EXIT_WINDOW_MS) {
            finish();
        } else {
            lastBackAt = now;
            Toast.makeText(this, "Press BACK again to exit", Toast.LENGTH_SHORT).show();
            setChromeVisible(true);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        ApiCompat.goImmersive(getWindow().getDecorView());
        if (!noisyRegistered) {
            registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        }
        if (engine != null && currentUrl != null && !currentUrl.isEmpty() && !engine.isPlaying()) {
            engine.play();
        }
        setChromeVisible(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (noisyRegistered) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (IllegalArgumentException ignored) {
                // already gone
            }
            noisyRegistered = false;
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (engine != null) {
            engine.release();
            engine = null;
        }
        if (io != null) {
            io.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ApiCompat.goImmersive(getWindow().getDecorView());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateProgress();
    }
}
