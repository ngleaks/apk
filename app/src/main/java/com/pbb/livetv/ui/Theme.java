package com.pbb.livetv.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;

/** Design tokens. One place for spacing, radii, colours and type so every screen matches. */
public final class Theme {

    // Colours (kept in sync with res/values/colors.xml for the XML side)
    public static final int BG = 0xFF05070C;
    public static final int PANEL = 0xFF0B0F17;
    public static final int TILE = 0xFF0E131C;
    public static final int TILE_FOCUS = 0xFF14222E;
    public static final int ACCENT = 0xFF33DDBB;
    public static final int ACCENT_DIM = 0xFF1B7F72;
    public static final int TEXT = 0xFFF2F5FF;
    public static final int TEXT_SECOND = 0xFF8A94A8;
    public static final int TEXT_MUTED = 0xFF5A6478;
    public static final int LIVE = 0xFFFF4D5E;
    public static final int WARN = 0xFFFFB020;
    public static final int BORDER = 0xFF1E2532;
    public static final int SCRIM = 0xB3000000;

    // Spacing
    public static final int GUTTER = 28;   // dp, screen edge
    public static final int GAP = 12;      // dp, between tiles
    public static final int RADIUS = 10;   // dp

    public static Typeface regular;
    public static Typeface medium;
    public static Typeface bold;

    private Theme() {
    }

    public static void init(Context context) {
        if (regular == null) {
            regular = Typeface.create("sans-serif", Typeface.NORMAL);
            medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            bold = Typeface.create("sans-serif", Typeface.BOLD);
        }
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static float sp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics()) / (context.getResources().getDisplayMetrics().scaledDensity);
    }

    public static GradientDrawable rounded(Context context, int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    public static GradientDrawable outlined(Context context, int fill, int stroke, int strokeWidthDp,
                                            int radiusDp) {
        GradientDrawable d = rounded(context, fill, radiusDp);
        if (strokeWidthDp > 0) {
            d.setStroke(dp(context, strokeWidthDp), stroke);
        }
        return d;
    }

    public static void setAlpha(View view, float alpha) {
        view.setAlpha(alpha);
    }

    public static int blend(int from, int to, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * clamped);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * clamped);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * clamped);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * clamped);
        return Color.argb(a, r, g, b);
    }
}
