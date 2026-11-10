package com.pbb.livetv.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pbb.livetv.Camera;

/**
 * One CCTV tile: a 16:9 video surface with an overlay that is always readable from the sofa.
 *
 * <p>States the tile can be in, all rendered honestly:
 * <ul>
 *     <li><b>playable</b> – video surface, label, live dot, buffering spinner while connecting;</li>
 *     <li><b>no stream</b> – dashed placeholder with "No stream configured" (never fake video);</li>
 *     <li><b>licence required</b> – ClearKey feed without supplied keys;</li>
 *     <li><b>error</b> – reason text plus a retry affordance hint.</li>
 * </ul>
 * Used for the single-camera sections (as the main video surface) and for the ALL grid.
 */
public final class TileView extends FrameLayout {

    public interface SurfaceCallback {
        void onSurface(TileView tile, Surface surface);

        void onSurfaceLost(TileView tile);
    }

    private final boolean gridMode;
    private final FrameLayout videoHost;
    private final TextView labelView;
    private final TextView statusView;
    private final TextView metaView;
    private final LinearLayout centreBox;
    private final TextView centreTitle;
    private final TextView centreBody;
    private final android.widget.ProgressBar spinner;
    private final View focusRing;
    private final View liveDot;
    private final TextView badgeView;

    private SurfaceCallback surfaceCallback;
    private Camera camera;
    private SurfaceView surfaceView;
    private TextureView textureView;
    private android.widget.ProgressBar pendingSpinner;

    public TileView(Context context, boolean gridMode) {
        super(context);
        this.gridMode = gridMode;
        setClipToPadding(false);
        setBackgroundColor(Color.BLACK);

        videoHost = new FrameLayout(context);
        addView(videoHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Bottom scrim + label block
        View scrim = new View(context);
        GradientDrawable scrimDrawable = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x66000000, 0x00000000});
        scrim.setBackground(scrimDrawable);
        LayoutParams scrimParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Theme.dp(context, gridMode ? 64 : 120), android.view.Gravity.BOTTOM);
        addView(scrim, scrimParams);

        LinearLayout labelBlock = UiKit.column(context);
        int pad = Theme.dp(context, gridMode ? 12 : 24);
        labelBlock.setPadding(pad, pad / 2, pad, Theme.dp(context, gridMode ? 10 : 20));

        LinearLayout titleRow = UiKit.row(context);
        liveDot = UiKit.dot(context, Theme.LIVE, gridMode ? 8 : 10);
        titleRow.addView(liveDot);
        LinearLayout.LayoutParams dotLp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dotLp.leftMargin = Theme.dp(context, 8);
        labelView = UiKit.heading(context, "", gridMode ? 15f : 22f, Theme.TEXT);
        titleRow.addView(labelView, dotLp);
        labelBlock.addView(titleRow);

        metaView = UiKit.label(context, "", gridMode ? 11f : 14f, Theme.TEXT_SECOND);
        LinearLayout.LayoutParams metaLp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.leftMargin = Theme.dp(context, 16);
        metaLp.topMargin = Theme.dp(context, 2);
        labelBlock.addView(metaView, metaLp);

        LayoutParams labelParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM);
        addView(labelBlock, labelParams);
        labelBlock.setClickable(false);

        // Centre state box (connecting / buffering / no stream / error)
        centreBox = UiKit.column(context);
        centreBox.setGravity(android.view.Gravity.CENTER);
        centreBox.setClickable(false);
        spinner = UiKit.spinner(context, gridMode ? 26 : 42, Theme.ACCENT);
        centreBox.addView(spinner);
        centreTitle = UiKit.heading(context, "", gridMode ? 13f : 20f, Theme.TEXT);
        centreTitle.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams ctLp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ctLp.topMargin = Theme.dp(context, gridMode ? 6 : 12);
        centreBox.addView(centreTitle, ctLp);
        centreBody = UiKit.label(context, "", gridMode ? 10.5f : 14f, Theme.TEXT_SECOND);
        centreBody.setGravity(android.view.Gravity.CENTER);
        centreBody.setLineSpacing(Theme.dp(context, 2), 1f);
        centreBody.setMaxWidth(Theme.dp(context, gridMode ? 260 : 560));
        LinearLayout.LayoutParams cbLp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = Theme.dp(context, 4);
        centreBox.addView(centreBody, cbLp);
        LayoutParams centreParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER);
        addView(centreBox, centreParams);

        // Top-right badge (format / order)
        badgeView = UiKit.caption(context, "", gridMode ? 9f : 11f, Theme.TEXT);
        badgeView.setBackground(Theme.rounded(context, 0xB30B0F17, 4));
        badgeView.setPadding(Theme.dp(context, 8), Theme.dp(context, 3),
                Theme.dp(context, 8), Theme.dp(context, 3));
        LayoutParams badgeParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.END);
        badgeParams.topMargin = Theme.dp(context, gridMode ? 10 : 20);
        badgeParams.rightMargin = Theme.dp(context, gridMode ? 10 : 24);
        addView(badgeView, badgeParams);

        statusView = UiKit.label(context, "", 1f, Color.TRANSPARENT);
        addView(statusView);

        // Focus ring painted above everything, only in grid mode
        focusRing = new View(context);
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(Theme.dp(context, Theme.RADIUS));
        ring.setStroke(Theme.dp(context, 3), Theme.ACCENT);
        focusRing.setBackground(ring);
        focusRing.setVisibility(View.GONE);
        addView(focusRing, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setFocusable(gridMode);
        setFocusableInTouchMode(false);
        setOnFocusChangeListener((v, hasFocus) -> setFocused(hasFocus));
    }

    public boolean isGridMode() {
        return gridMode;
    }

    public Camera camera() {
        return camera;
    }

    public void bind(Camera camera) {
        this.camera = camera;
        labelView.setText(camera == null ? "" : camera.label);
        metaView.setText(camera == null ? "" : camera.subtitle);
        badgeView.setText(camera == null ? "" : String.format("CAM %02d", camera.order));
        boolean live = camera != null && camera.playable();
        liveDot.setVisibility(live ? View.VISIBLE : View.GONE);
        statusView.setContentDescription(camera == null ? "" : camera.label);
    }

    public void invalidateVideo() {
        removeVideoViews();
    }

    /** True when a video surface is already attached (avoids recreating SurfaceViews needlessly). */
    public boolean hasVideo() {
        return surfaceView != null || textureView != null;
    }

    public void setSurfaceCallback(SurfaceCallback callback) {
        this.surfaceCallback = callback;
    }

    /** Attach a video surface. Returns the Surface to hand to the player (may be null until ready). */
    public Surface attachVideo(boolean preferTexture) {
        removeVideoViews();
        if (preferTexture) {
            textureView = new TextureView(getContext());
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                    if (surfaceCallback != null) {
                        surfaceCallback.onSurface(TileView.this, new Surface(st));
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    if (surfaceCallback != null) {
                        surfaceCallback.onSurfaceLost(TileView.this);
                    }
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                }
            });
            videoHost.addView(textureView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return textureView.isAvailable() && textureView.getSurfaceTexture() != null
                    ? new Surface(textureView.getSurfaceTexture()) : null;
        }
        surfaceView = new SurfaceView(getContext());
        surfaceView.getHolder().setFormat(android.graphics.PixelFormat.RGBX_8888);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (surfaceCallback != null) {
                    surfaceCallback.onSurface(TileView.this, holder.getSurface());
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (surfaceCallback != null) {
                    surfaceCallback.onSurface(TileView.this, holder.getSurface());
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (surfaceCallback != null) {
                    surfaceCallback.onSurfaceLost(TileView.this);
                }
            }
        });
        videoHost.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return surfaceView.getHolder().getSurface() != null && surfaceView.getHolder().getSurface().isValid()
                ? surfaceView.getHolder().getSurface() : null;
    }

    private void removeVideoViews() {
        videoHost.removeAllViews();
        surfaceView = null;
        textureView = null;
    }

    /** Playback state rendering. Kept in one method so grid and fullscreen behave identically. */
    public void showState(PlayerUi.State state, String detail) {
        switch (state) {
            case CONNECTING:
                centreBox.setVisibility(View.VISIBLE);
                spinner.setVisibility(View.VISIBLE);
                centreTitle.setText("Connecting…");
                centreTitle.setTextColor(Theme.TEXT);
                centreBody.setText(detail == null ? "" : detail);
                centreBody.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
                break;
            case BUFFERING:
                centreBox.setVisibility(View.VISIBLE);
                spinner.setVisibility(View.VISIBLE);
                centreTitle.setText("Buffering…");
                centreTitle.setTextColor(Theme.TEXT);
                centreBody.setText("");
                centreBody.setVisibility(View.GONE);
                break;
            case PLAYING:
                centreBox.setVisibility(View.GONE);
                spinner.setVisibility(View.GONE);
                break;
            case PAUSED:
                centreBox.setVisibility(View.VISIBLE);
                spinner.setVisibility(View.GONE);
                centreTitle.setText("Paused");
                centreTitle.setTextColor(Theme.TEXT);
                centreBody.setText("OK to resume");
                centreBody.setVisibility(View.VISIBLE);
                break;
            case NO_STREAM:
                centreBox.setVisibility(View.VISIBLE);
                spinner.setVisibility(View.GONE);
                centreTitle.setText("No stream configured");
                centreTitle.setTextColor(Theme.TEXT_SECOND);
                centreBody.setText(camera != null && camera.awaitingKey()
                        ? "ClearKey licence not provided for this feed"
                        : "Add an authorised stream URL in assets/streams.json");
                centreBody.setVisibility(View.VISIBLE);
                break;
            case LICENCE_REQUIRED:
                centreBox.setVisibility(View.VISIBLE);
                spinner.setVisibility(View.GONE);
                centreTitle.setText("Licence required");
                centreTitle.setTextColor(Theme.WARN);
                centreBody.setText("This feed is ClearKey protected. Supply licence material you are "
                        + "authorised to use – keys are never bundled or bypassed.");
                centreBody.setVisibility(View.VISIBLE);
                break;
            case ERROR:
                centreBox.setVisibility(View.VISIBLE);
                spinner.setVisibility(View.GONE);
                centreTitle.setText("Stream unavailable");
                centreTitle.setTextColor(Theme.LIVE);
                centreBody.setText(detail == null || detail.isEmpty()
                        ? "Press OK to retry" : detail + "\nPress OK to retry");
                centreBody.setVisibility(View.VISIBLE);
                break;
            default:
                centreBox.setVisibility(View.VISIBLE);
                spinner.setVisibility(View.GONE);
                break;
        }
    }

    private void setFocused(boolean focused) {
        focusRing.setVisibility(focused ? View.VISIBLE : View.GONE);
        setBackgroundColor(focused ? Theme.TILE_FOCUS : Color.BLACK);
        animate().scaleX(focused ? 1.018f : 1f).scaleY(focused ? 1.018f : 1f)
                .setDuration(140L).start();
    }

    public void setSelectedMark(boolean selected) {
        // Small accent underline for "this is the camera the player is showing" in grid mode.
        focusRing.setVisibility(selected || hasFocus() ? View.VISIBLE : View.GONE);
    }
}
