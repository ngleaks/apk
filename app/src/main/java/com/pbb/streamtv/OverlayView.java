package com.pbb.streamtv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Locale;

/**
 * Everything drawn over the video, on one custom view: title, state pill, progress bar and the
 * D-pad key hints. Doing it by hand keeps the APK free of androidx and means the layout is exactly
 * the same on a 720p TV stick and a 4K panel.
 */
final class OverlayView extends View {

    interface Actions {
        void onSeekRequested(long positionMs);
    }

    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF scratch = new RectF();

    private String title = "";
    private String subtitle = "";
    private String hint = "";
    private String centre = "";
    private int centreLines = 0;

    private boolean live = true;
    private boolean showProgress = false;
    private long position = 0L;
    private long duration = 0L;
    private long lastProgressAt = 0L;

    private float alphaUi = 1f;
    private ValueAnimator fade;
    private Bitmap logo;
    private boolean showLogo = false;

    OverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        box.setColor(0xB3000000);
        accent.setColor(0xFF33DDBB);
        track.setColor(0x44FFFFFF);
    }

    void setTitle(String title, String subtitle, boolean live) {
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.live = live;
        invalidate();
    }

    void setHint(String hint) {
        this.hint = hint == null ? "" : hint;
        invalidate();
    }

    void setCentreMessage(String message, int lines) {
        this.centre = message == null ? "" : message;
        this.centreLines = lines;
        invalidate();
    }

    /** Brand mark shown on the startup screen only; it disappears once video starts. */
    void setLogo(Bitmap bitmap) {
        this.logo = bitmap;
        invalidate();
    }

    void setShowLogo(boolean show) {
        this.showLogo = show;
        invalidate();
    }

    void setProgress(long positionMs, long durationMs, boolean seekable) {
        this.position = positionMs;
        this.duration = durationMs;
        this.showProgress = seekable && durationMs > 0L;
        this.lastProgressAt = System.currentTimeMillis();
        invalidate();
    }

    /** Slide the on-screen chrome in or out. */
    void fadeUi(final boolean visible) {
        if (fade != null) {
            fade.cancel();
        }
        fade = ValueAnimator.ofFloat(alphaUi, visible ? 1f : 0f);
        fade.setDuration(visible ? 160L : 320L);
        fade.setInterpolator(new DecelerateInterpolator());
        fade.addUpdateListener(a -> {
            alphaUi = (float) a.getAnimatedValue();
            invalidate();
        });
        fade.start();
    }

    boolean uiVisible() {
        return alphaUi > 0.05f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float density = getResources().getDisplayMetrics().density;
        if (w == 0 || h == 0) {
            return;
        }
        int save = canvas.saveLayerAlpha(0, 0, w, h, (int) (alphaUi * 255));

        float pad = 28f * density;
        // ---- header: title on the left, state pill on the right ----
        if (!title.isEmpty()) {
            text.setTextSize(26f * density);
            text.setColor(Color.WHITE);
            text.setShadowLayer(8f, 0f, 2f, Color.BLACK);
            canvas.drawText(title, pad, pad + 8f * density + text.getTextSize(), text);

            String pill = live ? "LIVE" : (subtitle.isEmpty() ? "" : subtitle);
            if (!pill.isEmpty()) {
                text.setTextSize(13f * density);
                float pillTextWidth = text.measureText(pill);
                float pillW = pillTextWidth + 26f * density;
                float pillH = 30f * density;
                scratch.set(pad, pad + 18f * density + text.getTextSize(), pad + pillW, pad + 18f * density + text.getTextSize() + pillH);
                box.setColor(live ? 0xCC2A0B12 : 0x99101828);
                canvas.drawRoundRect(scratch, pillH / 2f, pillH / 2f, box);
                accent.setColor(live ? 0xFFFF4D6A : 0xFF33DDBB);
                canvas.drawCircle(scratch.left + 13f * density, scratch.centerY(), 4f * density, accent);
                text.setColor(live ? 0xFFFF8FA2 : 0xFFB9C6FF);
                canvas.drawText(pill, scratch.left + 22f * density, scratch.centerY() + text.getTextSize() / 3f, text);
            }
        }

        // ---- progress (only for seekable content) ----
        if (showProgress && alphaUi > 0.3f) {
            float barH = 4f * density;
            float barY = h - pad - 44f * density;
            scratch.set(pad, barY, w - pad, barY + barH);
            canvas.drawRoundRect(scratch, barH / 2f, barH / 2f, track);
            float ratio = Math.min(1f, Math.max(0f, (float) position / (float) duration));
            scratch.right = pad + (w - pad * 2f) * ratio;
            accent.setColor(0xFF33DDBB);
            canvas.drawRoundRect(scratch, barH / 2f, barH / 2f, accent);

            text.setTextSize(12f * density);
            text.setColor(0xFFCCD3EA);
            text.setShadowLayer(6f, 0f, 1f, Color.BLACK);
            canvas.drawText(format(position) + "  /  " + format(duration), pad, barY - 8f * density, text);
        }

        // ---- footer hint ----
        if (!hint.isEmpty() && alphaUi > 0.3f) {
            text.setTextSize(13f * density);
            text.setColor(0xFFB9C6FF);
            text.setShadowLayer(6f, 0f, 1f, Color.BLACK);
            canvas.drawText(hint, pad, h - pad, text);
        }

        // ---- startup brand mark ----
        if (showLogo && logo != null && !logo.isRecycled()) {
            float logoW = Math.min(w * 0.42f, h * 0.72f);
            float logoH = logoW * logo.getHeight() / (float) logo.getWidth();
            float left = (w - logoW) / 2f;
            float top = h * 0.42f - logoH / 2f;
            canvas.drawBitmap(logo, null, new RectF(left, top, left + logoW, top + logoH), null);
        }

        // ---- centre message (buffering / errors) ----
        if (!centre.isEmpty()) {
            String[] lines = centre.split("\n");
            text.setTextSize(16f * density);
            text.setShadowLayer(8f, 0f, 2f, Color.BLACK);
            float lineH = text.getTextSize() * 1.35f;
            float blockH = lineH * lines.length;
            float widest = 0f;
            for (String line : lines) {
                widest = Math.max(widest, text.measureText(line));
            }
            scratch.set((w - widest) / 2f - 26f * density, (h - blockH) / 2f - 20f * density,
                    (w + widest) / 2f + 26f * density, (h + blockH) / 2f + 20f * density);
            box.setColor(0x99000000);
            canvas.drawRoundRect(scratch, 14f * density, 14f * density, box);
            text.setColor(Color.WHITE);
            float y = (h - blockH) / 2f + text.getTextSize();
            for (String line : lines) {
                canvas.drawText(line, (w - text.measureText(line)) / 2f, y, text);
                y += lineH;
            }
        }

        canvas.restoreToCount(save);
    }

    static String format(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}
