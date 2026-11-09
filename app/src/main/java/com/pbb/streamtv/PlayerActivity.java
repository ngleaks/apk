package com.pbb.streamtv;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen + full-screen player in one D-pad driven activity.
 *
 * <p>Why not a WebView? A website cannot be focused with a TV remote and drags in desktop
 * assumptions. Here nothing remote is ever rendered: the picker only holds stream URLs and playback
 * happens through {@link StreamEngine} (HLS segments or a progressive file), so the hardware
 * decoders, audio focus and the remote's media keys all behave like a TV app should.
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

    /** True while the player stage (video + chrome) fills the screen; false on the home screen. */
    private boolean watching = false;

    private AudioManager audioManager;
    private boolean noisyRegistered = false;

    private String currentUrl = "";
    private String currentTitle = "";
    private String currentKey = "";
    /** URL waiting for "Try again" from the error card / Add stream dialog. */
    private String pendingUrl = "";
    private String pendingTitle = "";
    private String pendingKey = "";
    private int retryIndex = 0;
    private long lastBackAt = 0L;
    private boolean chromeVisible = true;

    // ---------------------------------------------------------------- home screen widgets
    private View home;
    private LinearLayout homeTitle;
    private TextView homeSubtitle;
    private LinearLayout homeCards;
    private LinearLayout homeCameras;
    private TextView homeHint;

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
        overlay.setHint(OverlayView.HINT_WATCHING);
        overlay.setLogo(BitmapFactory.decodeResource(getResources(), R.drawable.splash_logo));
        overlay.setShowLogo(true);
        root.addView(overlay);

        home = buildHome();
        root.addView(home);

        setContentView(root);
        showHome();
    }

    /** The TV home screen: three camera cards, one row of actions. Nothing to scroll, nothing tiny. */
    private View buildHome() {
        FrameLayout stage = new FrameLayout(this);
        stage.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.setBackgroundColor(Ui.HOME_BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        stage.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Ui.dp(this, 48), Ui.dp(this, 26), Ui.dp(this, 48), Ui.dp(this, 22));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        homeTitle = new LinearLayout(this);
        homeTitle.setOrientation(LinearLayout.HORIZONTAL);
        homeTitle.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(homeTitle);

        homeSubtitle = Ui.label(this, "PBB 3.0 · All Access", 17f, Ui.TEXT_MUTED, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(this, 6);
        homeSubtitle.setLayoutParams(subLp);
        column.addView(homeSubtitle);

        // ---- camera cards, the main thing on screen ----
        TextView camHeader = Ui.label(this, "LIVE CAMERAS", 13f, Ui.ACCENT, false);
        camHeader.setLetterSpacing(0.18f);
        LinearLayout.LayoutParams camHeaderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        camHeaderLp.topMargin = Ui.dp(this, 20);
        camHeader.setLayoutParams(camHeaderLp);
        column.addView(camHeader);

        homeCameras = new LinearLayout(this);
        homeCameras.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams camLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        camLp.topMargin = Ui.dp(this, 8);
        homeCameras.setLayoutParams(camLp);
        column.addView(homeCameras);

        // ---- actions ----
        TextView actionHeader = Ui.label(this, "MORE", 13f, Ui.ACCENT, false);
        actionHeader.setLetterSpacing(0.18f);
        LinearLayout.LayoutParams actionHeaderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionHeaderLp.topMargin = Ui.dp(this, 20);
        actionHeader.setLayoutParams(actionHeaderLp);
        column.addView(actionHeader);

        homeCards = new LinearLayout(this);
        homeCards.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardsLp.topMargin = Ui.dp(this, 8);
        homeCards.setLayoutParams(cardsLp);
        column.addView(homeCards);

        homeHint = Ui.label(this,
                "OK watch   ·   ← → pick   ·   ▲ ▼ move   ·   long OK remove   ·   BACK exit",
                12f, Ui.TEXT_FAINT, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = Ui.dp(this, 16);
        homeHint.setLayoutParams(hintLp);
        column.addView(homeHint);

        stage.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return stage;
    }

    private void showHome() {
        watching = false;
        home.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.GONE);
        overlay.setShowLogo(false);
        overlay.setCentreMessage("", 0);
        overlay.setHint(OverlayView.HINT_WATCHING);
        setChromeVisible(false);
        rebuildHome();
    }

    private void rebuildHome() {
        // Brand row: logo art + wordmark, so the home screen reads like an app, not a debug menu.
        homeTitle.removeAllViews();
        android.widget.ImageView mark = new android.widget.ImageView(this);
        mark.setImageResource(R.drawable.splash_logo);
        mark.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        homeTitle.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        TextView word = Ui.label(this, "PBBforFREE", 28f, Color.WHITE, true);
        LinearLayout.LayoutParams wordLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wordLp.leftMargin = Ui.dp(this, 16);
        word.setLayoutParams(wordLp);
        homeTitle.addView(word);
        TextView tv = Ui.label(this, " TV ", 15f, Ui.HOME_BG, true);
        tv.setBackground(Ui.rounded(Ui.ACCENT, 0, 0, Ui.dp(this, 6)));
        tv.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.leftMargin = Ui.dp(this, 10);
        tv.setLayoutParams(tvLp);
        homeTitle.addView(tv);

        // ---- cameras: three equal cards, filled edge to edge ----
        homeCameras.removeAllViews();
        List<ChannelStore.Channel> channels = ChannelStore.load(this);
        View firstCamera = null;
        boolean focusAssigned = false;
        for (final ChannelStore.Channel channel : channels) {
            if (!channel.isCamera()) {
                continue;
            }
            boolean demo = "demo".equals(channel.key);
            boolean hasResolver = !ChannelStore.resolverFor(this, channel.key).isEmpty();
            boolean ready = channel.isBound() || hasResolver;
            String badge = demo ? "TEST" : (ready ? "READY" : "NOT SET");
            String detail = demo
                    ? "Apple sample stream · proves the player works"
                    : (channel.isBound()
                            ? "Plays straight on this TV, no login"
                            : (hasResolver
                                    ? "Live link fetched each time you press OK"
                                    : "Bind a stream URL once — press OK"));
            Ui.Card card = Ui.card(this, "CAM", badgeColor(channel),
                    cameraGlyph(channel), channel.title, detail, ready, 176);
            card.view.setOnClickListener(v -> openChannel(channel));
            card.view.setOnLongClickListener(v -> {
                if (!channel.isBound()) {
                    Toast.makeText(this, "Nothing to remove yet", Toast.LENGTH_SHORT).show();
                } else {
                    ChannelStore.remove(this, channel);
                    Toast.makeText(this, "Removed " + channel.title, Toast.LENGTH_SHORT).show();
                    rebuildHome();
                }
                return true;
            });
            if (homeCameras.getChildCount() > 0) {
                card.view.setLayoutParams(Ui.cardParams(this, 1f, 18));
            } else {
                card.view.setLayoutParams(Ui.cardParams(this, 1f, 0));
            }
            homeCameras.addView(card.view);
            card.badge.setText(badge);
            // Focus the last tuned camera, else the first bound one, else the first slot.
            boolean preferred = (!currentKey.isEmpty() && currentKey.equals(channel.key))
                    || (!focusAssigned
                        && (channel.isBound() || channelIndexIsFirstCamera(channels, channel)));
            if (preferred && firstCamera == null) {
                firstCamera = card.view;
                focusAssigned = true;
            }
        }

        // ---- actions ----
        homeCards.removeAllViews();
        Ui.Card add = Ui.cardRow(this, "SETUP", Ui.ACCENT,
                "+", "Add a stream",
                "Paste a .m3u8 link with the on-screen keyboard, or push it from a PC over adb.",
                96);
        add.view.setOnClickListener(v -> showAddDialog());
        addCard(add);

        Ui.Card site = Ui.cardRow(this, "SOURCE", Ui.WARN,
                "↗", "PBB website",
                "Opens pbblivestream.vercel.app in the system browser when you need the link text.",
                96);
        site.view.setOnClickListener(v -> openExternal(StreamConfig.LANDING_PAGE));
        addCard(site);

        Ui.Card restore = Ui.cardRow(this, "RESET", Ui.TEXT_MUTED,
                "↺", "Restore cameras",
                "Brings back camera slots removed with a long OK press, and forgets the auto-open.",
                96);
        restore.view.setOnClickListener(v -> {
            ChannelStore.restoreCameras(this);
            getSharedPreferences(StreamConfig.PREFS, MODE_PRIVATE).edit()
                    .remove(StreamConfig.KEY_LAST_URL)
                    .remove(StreamConfig.KEY_LAST_TITLE)
                    .remove(StreamConfig.KEY_LAST_KEY).apply();
            currentKey = "";
            rebuildHome();
        });
        addCard(restore);

        Ui.Card importCard = Ui.cardRow(this, "LINKS", Ui.ACCENT,
                "⇣", "Import links",
                "Pulls a small JSON link file (one URL or one resolver endpoint per camera) and binds all three cameras at once.",
                96);
        importCard.view.setOnClickListener(v -> showImportDialog());
        addCard(importCard);

        int bound = ChannelStore.boundCount(this);
        homeSubtitle.setText(bound == 0
                ? "PBB 3.0 · All Access — no camera linked yet"
                : "PBB 3.0 · All Access — " + bound + " of " + StreamConfig.MANIFEST.length + " cameras ready");

        if (firstCamera != null) {
            firstCamera.requestFocus();
        }
    }

    private boolean channelIndexIsFirstCamera(List<ChannelStore.Channel> all, ChannelStore.Channel c) {
        for (ChannelStore.Channel other : all) {
            if (other.isCamera()) {
                return other == c;
            }
        }
        return false;
    }

    private static int badgeColor(ChannelStore.Channel channel) {
        if ("demo".equals(channel.key)) {
            return Ui.WARN;
        }
        return channel.isBound() ? Ui.ACCENT : Ui.TEXT_MUTED;
    }

    /** "Main stream · All Access" -> "MAIN STREAM"; "Dining area" -> "DINING AREA". */
    private static String cameraShortName(ChannelStore.Channel channel) {
        String title = channel.title;
        int cut = title.indexOf(" · ");
        if (cut > 0) {
            title = title.substring(0, cut);
        }
        return title.toUpperCase(java.util.Locale.US);
    }

    private void addCard(Ui.Card card) {
        LinearLayout.LayoutParams lp = cardParams(1f, homeCards.getChildCount() == 0 ? 0 : 18);
        card.view.setLayoutParams(lp);
        homeCards.addView(card.view);
    }

    /** Weight-based row params with a fixed pixel height already set by {@link Ui#cardRow}. */
    private LinearLayout.LayoutParams cardParams(float weight, int leftMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 96), weight);
        lp.leftMargin = Ui.dp(this, leftMarginDp);
        return lp;
    }

    /** Big glyph per camera, so the three cards are distinguishable without reading. */
    private static String cameraGlyph(ChannelStore.Channel channel) {
        switch (channel.key) {
            case "all":
                return "▣";
            case "dining":
                return "⌂";
            case "external":
                return "☀";
            default:
                return "▶";
        }
    }

    // ------------------------------------------------------------------ binding / adding streams

    private void openChannel(ChannelStore.Channel channel) {
        String resolver = ChannelStore.resolverFor(this, channel.key);
        if (channel.isBound()) {
            play(channel.url, channel.title, channel.key);
        } else if (!resolver.isEmpty()) {
            playResolved(channel.key, channel.title, resolver);
        } else {
            showBindDialog(channel);
        }
    }

    /**
     * Ask a resolver endpoint for the current URL of a camera, then play it. Runs off the main
     * thread; the overlay shows "Fetching link…" meanwhile so a slow endpoint never looks frozen.
     */
    private void playResolved(String key, String title, String resolverUrl) {
        final String label = title == null || title.isEmpty() ? StreamConfig.titleForKey(key) : title;
        watching = true;
        home.setVisibility(View.GONE);
        overlay.setVisibility(View.VISIBLE);
        overlay.setShowLogo(false);
        overlay.setTitle(label, "", true);
        overlay.setCentreMessage("Fetching link…", 1);
        setChromeVisible(true);
        ui.removeCallbacks(retryRunnable);
        if (engine != null) {
            engine.release();
            engine = null;
        }
        currentKey = key == null ? "" : key;
        currentTitle = label;
        currentUrl = "";
        pendingUrl = resolverUrl;
        io.execute(() -> {
            String url = "";
            String error = "";
            try {
                url = RemoteSources.resolve(resolverUrl);
            } catch (Exception e) {
                error = e.getMessage() == null ? "resolver failed" : e.getMessage();
            }
            final String resolved = url;
            final String failure = error;
            ui.post(() -> {
                if (!watching) {
                    return;
                }
                if (!resolved.isEmpty()) {
                    play(resolved, label, currentKey);
                } else {
                    overlay.setCentreMessage("Could not get the link"
                            + (failure.isEmpty() ? "" : "\n" + failure)
                            + "\n\n← cameras   ·   OK change link", 4);
                    setChromeVisible(true);
                }
            });
        });
    }

    private void showBindDialog(final ChannelStore.Channel channel) {
        final EditText input = streamInput();
        new AlertDialog.Builder(this)
                .setTitle("Link " + channel.title)
                .setMessage("Paste the stream URL for this camera once. It is saved on this TV and "
                        + "plays from now on with no login or browser.")
                .setView(pad(input))
                .setPositiveButton("Save & play", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!ChannelStore.looksPlayable(url)) {
                        // Not a direct stream: keep it if it can hand one out at play time.
                        if (url.startsWith("http")) {
                            ChannelStore.bindResolver(this, channel.key, url);
                            rebuildHome();
                            playResolved(channel.key, channel.title, url);
                        } else {
                            notifyCouldNotSave(url);
                        }
                        return;
                    }
                    ChannelStore.bind(this, channel.key, channel.title, url);
                    ChannelStore.bindResolver(this, channel.key, "");
                    rebuildHome();
                    play(url, channel.title, channel.key);
                })
                .setNegativeButton("Cancel", null)
                .show();
        showKeyboard(input);
    }

    private void showImportDialog() {
        final EditText input = streamInput();
        input.setHint("https://…/pbb-links.json");
        String remembered = ChannelStore.lastRemote(this);
        if (!remembered.isEmpty()) {
            input.setText(remembered);
        } else if (!StreamConfig.REMOTE_CONFIG_URL.isEmpty()) {
            input.setText(StreamConfig.REMOTE_CONFIG_URL);
        }
        new AlertDialog.Builder(this)
                .setTitle("Import camera links")
                .setMessage("Point this at a JSON file that maps each camera to a stream URL or to a "
                        + "resolver endpoint:\n"
                        + "{\"channels\":[{\"key\":\"dining\",\"url\":\"https://…/dining.m3u8\"}]}")
                .setView(pad(input))
                .setPositiveButton("Import", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!url.startsWith("http")) {
                        notifyCouldNotSave(url);
                        return;
                    }
                    importLinks(url);
                })
                .setNegativeButton("Cancel", null)
                .show();
        showKeyboard(input);
    }

    private void importLinks(String configUrl) {
        Toast.makeText(this, "Importing links…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            String message;
            try {
                RemoteSources.Result result = RemoteSources.sync(this, configUrl);
                ChannelStore.rememberRemote(this, configUrl);
                message = result.summary();
            } catch (Exception e) {
                message = "Import failed: " + (e.getMessage() == null ? "bad link" : e.getMessage());
            }
            final String text = message;
            ui.post(() -> {
                if (!isFinishing()) {
                    rebuildHome();
                }
                Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void showAddDialog() {
        final EditText input = streamInput();
        new AlertDialog.Builder(this)
                .setTitle("Add a stream URL")
                .setMessage("HLS (.m3u8) or DASH (.mpd) links work. Tip: press OK on a camera card to "
                        + "bind it to one of the three PBB cameras instead.")
                .setView(pad(input))
                .setPositiveButton("Play", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!ChannelStore.looksPlayable(url)) {
                        notifyCouldNotSave(url);
                        return;
                    }
                    ChannelStore.upsertExtra(this, null, url);
                    rebuildHome();
                    play(url, null, "extra");
                })
                .setNegativeButton("Cancel", null)
                .show();
        showKeyboard(input);
    }

    private EditText streamInput() {
        EditText input = new EditText(this);
        input.setHint("https://…/live.m3u8");
        input.setSingleLine(true);
        input.setTextSize(16f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Ui.TEXT_FAINT);
        return input;
    }

    private View pad(View view) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), 0);
        box.addView(view);
        return box;
    }

    /** On a TV the soft keyboard is the only way to type a URL with just a remote. */
    private void showKeyboard(EditText input) {
        ui.postDelayed(() -> {
            input.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        }, 350L);
    }

    private void notifyCouldNotSave(String url) {
        Toast.makeText(this,
                url.isEmpty() ? "Paste a stream URL first" : "That does not look like a stream URL",
                Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ playback

    private void play(String url, String title, String key) {
        if (url == null || url.isEmpty()) {
            return;
        }
        ui.removeCallbacks(retryRunnable);
        if (engine != null) {
            engine.release();
        }
        watching = true;
        home.setVisibility(View.GONE);
        overlay.setVisibility(View.VISIBLE);
        overlay.setShowLogo(false);
        overlay.setHint(OverlayView.HINT_WATCHING);

        currentUrl = url;
        currentKey = key == null ? "" : key;
        currentTitle = StreamConfig.titleFor(url, title == null || title.isEmpty()
                ? StreamConfig.titleForKey(currentKey) : title);
        pendingUrl = "";
        retryIndex = 0;

        engine = HlsPlaylist.looksLikePlaylist(url) || isLikelyHls(url)
                ? new SegmentPlayer(this, io)
                : new FilePlayer(this);
        overlay.setCentreMessage("Connecting…\n" + currentTitle, 2);
        overlay.setTitle(currentTitle, "", engine.isLive());
        surfaceHost.requestFocus();
        engine.prepare(url);
        StreamConfig.remember(getSharedPreferences(StreamConfig.PREFS, MODE_PRIVATE),
                url, currentTitle, currentKey);
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
                + "\n\n← channels   ·   OK change link   ·   ▲▼ home", 5);
        ui.removeCallbacks(retryRunnable);
        ui.postDelayed(retryRunnable, delay);
    }

    /** Stop the dead stream and show the URL box pre-filled, so a rotated/expired link is a 2-tap fix. */
    private void fixCurrentLink() {
        final EditText input = streamInput();
        String resolver = ChannelStore.resolverFor(this, currentKey);
        input.setText(currentUrl.isEmpty() ? resolver : currentUrl);
        new AlertDialog.Builder(this)
                .setTitle("Change link · " + currentTitle)
                .setMessage("Reconnect this camera with a fresh stream URL.")
                .setView(pad(input))
                .setPositiveButton("Save & play", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!ChannelStore.looksPlayable(url)) {
                        notifyCouldNotSave(url);
                        return;
                    }
                    if (!currentKey.isEmpty() && !"extra".equals(currentKey)) {
                        ChannelStore.bind(this, currentKey, currentTitle, url);
                    } else {
                        ChannelStore.upsertExtra(this, null, url);
                    }
                    play(url, currentTitle, currentKey);
                })
                .setNegativeButton("Cancel", null)
                .show();
        showKeyboard(input);
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
                pendingUrl = "";
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
                overlay.setCentreMessage("End of stream\n\n← channels   ·   ▲▼ home", 3);
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
            showHome();
            return;
        }
        Uri data = intent.getData();
        if (data != null && StreamConfig.DEEPLINK_SCHEME.equals(data.getScheme())) {
            String host = data.getHost() == null ? "" : data.getHost();
            String url = data.getQueryParameter("url");
            String title = data.getQueryParameter("title");
            String key = data.getQueryParameter("key");
            if ("add".equals(host) && url != null) {
                ChannelStore.bind(this, key, title, url);
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                showHome();
                rebuildHome();
                return;
            }
            if (url != null && !url.isEmpty()) {
                play(url, title, key);
                return;
            }
        }
        String extra = intent.getStringExtra(EXTRA_URL);
        if (extra != null && !extra.isEmpty()) {
            play(extra, intent.getStringExtra(EXTRA_TITLE), "");
            return;
        }
        StreamConfig.PlayRequest request = StreamConfig.resolve(null,
                getSharedPreferences(StreamConfig.PREFS, MODE_PRIVATE));
        if (!request.isEmpty() && StreamConfig.isAutoResume()) {
            play(request.url, request.title, request.key);
            return;
        }
        if (StreamConfig.isAutoResume()) {
            SharedPreferences prefs = getSharedPreferences(StreamConfig.PREFS, MODE_PRIVATE);
            String lastKey = prefs.getString(StreamConfig.KEY_LAST_KEY, "");
            String resolver = ChannelStore.resolverFor(this, lastKey);
            if (!resolver.isEmpty()) {
                playResolved(lastKey, prefs.getString(StreamConfig.KEY_LAST_TITLE, ""), resolver);
                return;
            }
        }
        showHome();
        if (firstLaunch && StreamConfig.OPEN_SITE_WHEN_UNBOUND && ChannelStore.boundCount(this) == 0) {
            openExternal(StreamConfig.LANDING_PAGE);
        }
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
            case KeyEvent.KEYCODE_DPAD_UP:
                if (watching) {
                    showHome();
                    return true;
                }
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (watching) {
                    showHome();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (watching) {
                    seekBy(10_000L);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MENU:
                if (watching) {
                    showHome();
                }
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (watching) {
                    showHome();
                }
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
        if (watching) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (!chromeVisible) {
                    setChromeVisible(true);
                } else if (!currentUrl.isEmpty()) {
                    // OK on an error card = fix the link; otherwise play/pause.
                    if (overlay.centreVisible()) {
                        fixCurrentLink();
                    } else {
                        togglePlayPause();
                    }
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (!chromeVisible) {
                    setChromeVisible(true);
                }
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private void handleBack() {
        if (watching) {
            showHome();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackAt < EXIT_WINDOW_MS) {
            finish();
        } else {
            lastBackAt = now;
            Toast.makeText(this, "Press BACK again to exit", Toast.LENGTH_SHORT).show();
        }
    }

    private void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        overlay.fadeUi(visible);
        ui.removeCallbacks(hideChrome);
        if (visible && watching) {
            ui.postDelayed(hideChrome, CHROME_TIMEOUT_MS);
        }
    }

    private void pokeChrome() {
        setChromeVisible(true);
        updateProgress();
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
        if (watching) {
            setChromeVisible(true);
        }
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
        if (watching) {
            updateProgress();
        }
    }
}
