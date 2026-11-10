package com.pbb.livetv.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Small widget factory plus the focus animation every TV app needs. */
public final class UiKit {

    private UiKit() {
    }

    public static TextView text(Context context, CharSequence value, float sizeSp, int color,
                                TypefaceHolder face) {
        TextView v = new TextView(context);
        applyBase(v);
        v.setText(value);
        v.setTextSize(sizeSp);
        v.setTextColor(color);
        v.setTypeface(face == null ? Theme.regular : face.typeface);
        return v;
    }

    /** Typeface holder so callers do not need to null-check static fields. */
    public static final class TypefaceHolder {
        public final android.graphics.Typeface typeface;

        public TypefaceHolder(android.graphics.Typeface typeface) {
            this.typeface = typeface;
        }
    }

    public static TypefaceHolder regular() {
        return new TypefaceHolder(Theme.regular);
    }

    public static TypefaceHolder medium() {
        return new TypefaceHolder(Theme.medium);
    }

    public static TypefaceHolder bold() {
        return new TypefaceHolder(Theme.bold);
    }

    public static TextView label(Context context, CharSequence value, float sizeSp, int color) {
        return text(context, value, sizeSp, color, regular());
    }

    public static TextView heading(Context context, CharSequence value, float sizeSp, int color) {
        return text(context, value, sizeSp, color, medium());
    }

    /** Small uppercase caption with letter spacing, used for section headers and labels. */
    public static TextView caption(Context context, CharSequence value, float sizeSp, int color) {
        TextView v = text(context, value, sizeSp, color, medium());
        v.setLetterSpacing(0.14f);
        v.setAllCaps(true);
        return v;
    }

    public static ProgressBar spinner(Context context, int sizeDp, int tint) {
        ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyle);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Theme.dp(context, sizeDp), Theme.dp(context, sizeDp));
        bar.setLayoutParams(lp);
        if (Build.VERSION.SDK_INT >= 21) {
            bar.getIndeterminateDrawable().setTint(tint);
        }
        return bar;
    }

    /** Round status dot (live indicator). */
    public static View dot(Context context, int color, int sizeDp) {
        View v = new View(context);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        v.setBackground(d);
        v.setLayoutParams(new LinearLayout.LayoutParams(Theme.dp(context, sizeDp), Theme.dp(context, sizeDp)));
        return v;
    }

    public static LinearLayout row(Context context) {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout column(Context context) {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static FrameLayout frame(Context context) {
        FrameLayout f = new FrameLayout(context);
        return f;
    }

    public static LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    public static LinearLayout.LayoutParams lp(int width, int height, float weight) {
        return new LinearLayout.LayoutParams(width, height, weight);
    }

    public static FrameLayout.LayoutParams flp(int width, int height, int gravity) {
        return new FrameLayout.LayoutParams(width, height, gravity);
    }

    public static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams params, int l, int t, int r, int b) {
        params.setMargins(l, t, r, b);
        return params;
    }

    public static void padding(View v, int l, int t, int r, int b) {
        v.setPadding(l, t, r, b);
    }

    /** Shared TV focus treatment: scale + brighter surface + accent border, 140 ms. */
    public static void focusable(View view, FocusStyle style) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(false);
        view.setClickable(true);
        view.setBackground(style.background(false));
        view.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackground(style.background(hasFocus));
            v.animate()
                    .scaleX(hasFocus ? style.focusScale : 1f)
                    .scaleY(hasFocus ? style.focusScale : 1f)
                    .setDuration(140L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            if (style.onFocus != null) {
                style.onFocus.onFocus(hasFocus);
            }
        });
        view.setOnClickListener(v -> {
            if (style.onClick != null) {
                style.onClick.run();
            }
        });
    }

    /** Immutable description of how a focusable view looks in both states. */
    public static final class FocusStyle {
        final Context context;
        int fillNormal = Theme.TILE;
        int fillFocus = Theme.TILE_FOCUS;
        int strokeNormal = Theme.BORDER;
        int strokeFocus = Theme.ACCENT;
        int strokeWidthNormal = 1;
        int strokeWidthFocus = 2;
        int radius = Theme.RADIUS;
        float focusScale = 1.02f;
        Runnable onClick;
        FocusListener onFocus;

        public FocusStyle(Context context) {
            this.context = context;
        }

        public FocusStyle shape(int fillNormal, int fillFocus, int strokeFocus, int radius,
                                int strokeNormal, int strokeWidthNormal, int strokeWidthFocus) {
            this.fillNormal = fillNormal;
            this.fillFocus = fillFocus;
            this.strokeFocus = strokeFocus;
            this.radius = radius;
            this.strokeNormal = strokeNormal;
            this.strokeWidthNormal = strokeWidthNormal;
            this.strokeWidthFocus = strokeWidthFocus;
            return this;
        }

        public FocusStyle scale(float value) {
            this.focusScale = value;
            return this;
        }

        public FocusStyle onClick(Runnable action) {
            this.onClick = action;
            return this;
        }

        public FocusStyle onFocus(FocusListener listener) {
            this.onFocus = listener;
            return this;
        }

        @SuppressWarnings("deprecation")
        GradientDrawable background(boolean focused) {
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(Theme.dp(context, radius));
            d.setColor(focused ? fillFocus : fillNormal);
            d.setStroke(Theme.dp(context, focused ? strokeWidthFocus : strokeWidthNormal),
                    focused ? strokeFocus : strokeNormal);
            return d;
        }
    }

    public interface FocusListener {
        void onFocus(boolean focused);
    }

    /** Force a view to lay out like a TV element: no background ripples, no min touch padding. */
    public static void applyBase(View v) {
        v.setBackground(null);
    }

    /** Convenience: wrap a view in a padded container for the bottom nav bar. */
    public static LinearLayout wrap(Context context, View child, int padLeft, int padRight) {
        LinearLayout outer = row(context);
        outer.setPadding(padLeft, 0, padRight, 0);
        outer.addView(child);
        return outer;
    }

    public static void setMargins(View view, int l, int t, int r, int b) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).setMargins(l, t, r, b);
            view.setLayoutParams(lp);
        }
    }

    public static AttributeSet unused() {
        return null;
    }
}
