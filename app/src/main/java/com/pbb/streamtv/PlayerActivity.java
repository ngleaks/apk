package com.pbb.streamtv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.List;

/**
 * One full-screen, D-pad driven player.
 *
 * <p>Why not a WebView? A website cannot be focused with a TV remote and drags in desktop
 * assumptions. Here nothing remote is ever rendered: the channel list only holds stream URLs, and
 * playback always happens in Media3 (hardware decoding, HLS/DASH, audio focus, remote media keys).
 */
public final class PlayerActivity extends Activity {

    static final String EXTRA_URL = "extra_url";
    static final String EXTRA_TITLE = "extra_title";

    private static final long EXIT_WINDOW_MS = 2500L;
    private static final long[] RETRY_DELAYS_MS = {1_500L, 3_000L, 6_000L, 12_000L, 20_000L};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable retryRunnable = this::retryNow;

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView titleView;
    private TextView statusView;
    private TextView hintView;
    private ProgressBar spinner;
    private LinearLayout panel;
    private LinearLayout channelList;

    private String currentUrl = "";
    private String currentTitle = "";
    private int retryIndex = 0;
    private long lastBackAt = 0L;
    private boolean playingLive = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        buildPlayer();
        handleIntent(getIntent(), true);
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        playerView.setControllerShowTimeoutMs(4500);
        playerView.setFocusable(false);
        root.addView(playerView);

        spinner = new ProgressBar(this);
        spinner.setLayoutParams(new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        root.addView(spinner);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(16f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setVisibility(View.GONE);
        statusView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(statusView);

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setShadowLayer(8f, 0f, 2f, Color.BLACK);
        titleView.setVisibility(View.GONE);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.gravity = Gravity.TOP | Gravity.START;
        titleLp.setMargins(dp(28), dp(20), dp(28), 0);
        root.addView(titleView, titleLp);

        hintView = new TextView(this);
        hintView.setTextColor(0xFFB9C6FF);
        hintView.setTextSize(13f);
        hintView.setShadowLayer(8f, 0f, 2f, Color.BLACK);
        hintView.setText("OK  ·  play/pause      ←  ·  channels      BACK  ·  exit");
        hintView.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.BOTTOM | Gravity.START;
        hintLp.setMargins(dp(28), 0, 0, dp(18));
        root.addView(hintView, hintLp);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF20B1020);
        panel.setPadding(dp(22), dp(26), dp(22), dp(20));
        panel.setVisibility(View.GONE);
        panel.setFocusable(false);

        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        channelList = new LinearLayout(this);
        channelList.setOrientation(LinearLayout.VERTICAL);
        panel.addView(channelList, listLp);

        TextView footer = new TextView(this);
        footer.setText("ADD A CHANNEL FROM YOUR PC\nadb shell am start -a android.intent.action.VIEW \\\n  -d \"pbblivetv://add?url=https://…/live.m3u8&title=Main\" \\\n  com.pbb.streamtv");
        footer.setTextColor(0xFF8892B0);
        footer.setTextSize(10f);
        footer.setLineSpacing(dp(1), 1f);
        footer.setPadding(0, dp(10), 0, 0);
        panel.addView(footer);

        root.addView(panel, new FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        setContentView(root);
    }

    private void rebuildChannelButtons(@Nullable String focusUrl) {
        channelList.removeAllViews();

        TextView header = new TextView(this);
        header.setText("CHANNELS");
        header.setTextColor(0xFF9FB0FF);
        header.setTextSize(13f);
        header.setLetterSpacing(0.18f);
        header.setPadding(0, 0, 0, dp(14));
        channelList.addView(header);

        List<ChannelStore.Channel> channels = ChannelStore.load(this);
        if (channels.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No channels yet.\n\nAdd one with the adb command below, or fill in\nStreamConfig.MANIFEST and rebuild.");
            empty.setTextColor(0xFFCCD3EA);
            empty.setTextSize(12f);
            empty.setLineSpacing(dp(2), 1f);
            channelList.addView(empty);
        }

        for (ChannelStore.Channel channel : channels) {
            Button b = panelButton(channel.title, false);
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
            if (focusUrl != null && channel.url.equals(focusUrl)) {
                b.requestFocus();
            }
        }

        Button site = panelButton("Open website…", false);
        site.setOnClickListener(v -> {
            setPanelVisible(false);
            openExternal(StreamConfig.LANDING_PAGE);
        });
        channelList.addView(site);

        Button close = panelButton("Close", true);
        close.setOnClickListener(v -> setPanelVisible(false));
        channelList.addView(close);
    }

    private Button panelButton(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setBackground(buttonBackground(false, primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(9));
        b.setLayoutParams(lp);
        b.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackground(buttonBackground(hasFocus, primary));
            float scale = hasFocus ? 1.03f : 1f;
            v.setScaleX(scale);
            v.setScaleY(scale);
        });
        return b;
    }

    private GradientDrawable buttonBackground(boolean focused, boolean primary) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(focused ? 0x5533DDBB : (primary ? 0x442A3550 : 0x33FFFFFF));
        d.setCornerRadius(dp(10));
        if (focused) {
            d.setStroke(dp(2), 0xFF33DDBB);
        }
        return d;
    }

    private void setPanelVisible(boolean visible) {
        panel.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            rebuildChannelButtons(currentUrl.isEmpty() ? null : currentUrl);
            View first = channelList.getChildCount() > 1 ? channelList.getChildAt(1) : null;
            if (first != null) {
                first.requestFocus();
            }
            playerView.hideController();
        } else {
            playerView.requestFocus();
        }
    }

    // -------------------------------------------------------------- player

    private void buildPlayer() {
        player = new ExoPlayer.Builder(this).build();
        player.setHandleAudioBecomingNoisy(true);
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    spinner.setVisibility(View.VISIBLE);
                } else {
                    spinner.setVisibility(View.GONE);
                }
                if (state == Player.STATE_READY) {
                    retryIndex = 0;
                    statusView.setVisibility(View.GONE);
                    titleView.setText(currentTitle + (playingLive ? "   ·   LIVE" : ""));
                    titleView.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    spinner.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                scheduleRetry(error);
            }
        });
    }

    private void play(String url, @Nullable String title) {
        if (url == null || url.isEmpty()) {
            return;
        }
        ui.removeCallbacks(retryRunnable);
        currentUrl = url;
        currentTitle = StreamConfig.titleFor(url, title);
        playingLive = MediaUrls.isHls(url) || url.contains("live");
        retryIndex = 0;

        statusView.setVisibility(View.VISIBLE);
        statusView.setText("Loading…");
        spinner.setVisibility(View.VISIBLE);

        player.setMediaItem(new MediaItem.Builder().setUri(Uri.parse(url)).build());
        player.setPlayWhenReady(true);
        player.prepare();
        playerView.requestFocus();
        StreamConfig.remember(getSharedPreferences(StreamConfig.PREFS, MODE_PRIVATE), url, currentTitle);
    }

    private void scheduleRetry(@Nullable PlaybackException error) {
        spinner.setVisibility(View.GONE);
        long delay = RETRY_DELAYS_MS[Math.min(retryIndex, RETRY_DELAYS_MS.length - 1)];
        retryIndex++;
        String reason = error == null ? "" : describe(error);
        statusView.setVisibility(View.VISIBLE);
        statusView.setText("Stream unreachable" + (reason.isEmpty() ? "" : " · " + reason)
                + "\nRetrying in " + Math.max(1, delay / 1000) + "s…\n\nPress ← for channels");
        ui.removeCallbacks(retryRunnable);
        ui.postDelayed(retryRunnable, delay);
    }

    private void retryNow() {
        if (currentUrl.isEmpty()) {
            return;
        }
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private static String describe(PlaybackException error) {
        switch (error.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
                return "no connection";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "timeout";
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return "HTTP error";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
                return "bad playlist";
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
                return "decoder";
            default:
                return "error";
        }
    }

    // ---------------------------------------------------------- navigation

    private void handleIntent(@Nullable Intent intent, boolean firstLaunch) {
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
        StreamConfig.PlayRequest resume = StreamConfig.resolve(data, getSharedPreferences(StreamConfig.PREFS, MODE_PRIVATE));
        if (!resume.isEmpty() && StreamConfig.isAutoResume()) {
            play(resume.url, resume.title);
        } else {
            showStartup();
        }
    }

    private void showStartup() {
        spinner.setVisibility(View.GONE);
        statusView.setVisibility(View.VISIBLE);
        statusView.setText("No channel selected\n\nPress ← to pick a channel");
        setPanelVisible(true);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (panel.getVisibility() != View.VISIBLE) {
                    setPanelVisible(true);
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
            case KeyEvent.KEYCODE_MEDIA_STOP:
                player.stop();
                return true;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && panel.getVisibility() != View.VISIBLE
                && playerView.isControllerVisible()) {
            togglePlayPause();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBack();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void handleBack() {
        if (panel.getVisibility() == View.VISIBLE) {
            setPanelVisible(false);
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

    private void togglePlayPause() {
        if (player.isPlaying()) {
            player.pause();
        } else if (player.getPlaybackState() == Player.STATE_IDLE) {
            retryNow();
        } else {
            player.play();
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

    // ----------------------------------------------------------- lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        playerView.onResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        playerView.onPause();
        player.pause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        playerView.setPlayer(null);
        player.release();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
