package com.pbb.streamtv;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small factory for the handful of widgets the TV UI needs, styled to match the overlay. */
final class Ui {

    static final int ACCENT = 0xFF33DDBB;
    static final int PANEL_BG = 0xF20B1020;
    static final int TEXT_MUTED = 0xFF8892B0;

    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    static GradientDrawable rounded(int fill, int strokeWidth, int strokeColor, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokeWidth > 0) {
            d.setStroke(strokeWidth, strokeColor);
        }
        return d;
    }

    static TextView label(Context context, String text, float sizeSp, int color, boolean bold) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(sizeSp);
        v.setTextColor(color);
        if (bold) {
            v.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        }
        return v;
    }

    static Button button(Context context, String label) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 10));
        b.setBackground(rounded(0x33FFFFFF, 0, 0, dp(context, 10)));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(context, 9));
        b.setLayoutParams(lp);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(rounded(hasFocus ? 0x5533DDBB : 0x33FFFFFF,
                        hasFocus ? dp(v.getContext(), 2) : 0, ACCENT, dp(v.getContext(), 10)));
                float scale = hasFocus ? 1.03f : 1f;
                v.setScaleX(scale);
                v.setScaleY(scale);
            }
        });
        return b;
    }
}
