package com.pbb.streamtv;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small factory for the handful of widgets the TV UI needs, styled to match the overlay.
 *
 * <p>Everything is sized for a 10-foot viewing distance: 22sp card titles, generous padding, and
 * focus states that are visible from a couch (accent border + scale), because a TV has no pointer.
 */
final class Ui {

    static final int ACCENT = 0xFF33DDBB;
    static final int PANEL_BG = 0xF20B1020;
    static final int HOME_BG = 0xFF070A14;
    static final int TEXT_MUTED = 0xFF9FB0CC;
    static final int TEXT_FAINT = 0xFF66748F;
    static final int WARN = 0xFFFFC24B;
    static final int CARD_FILL = 0x14FFFFFF;
    static final int CARD_STROKE = 0x26FFFFFF;

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

    /** A focusable home-screen card: glyph, title, two lines of explanation, status pill. */
    static final class Card {
        final LinearLayout view;
        final TextView badge;
        final TextView glyph;
        final TextView title;
        final TextView detail;

        Card(LinearLayout view, TextView badge, TextView glyph, TextView title, TextView detail) {
            this.view = view;
            this.badge = badge;
            this.glyph = glyph;
            this.title = title;
            this.detail = detail;
        }
    }

    static Card card(Context context, String badgeText, int badgeTint, String glyphText,
                     String titleText, String detailText, boolean active) {
        return card(context, badgeText, badgeTint, glyphText, titleText, detailText, active, 0);
    }

    /** Same card with a fixed pixel height, so a row of cards lines up on every screen size. */
    static Card card(Context context, String badgeText, int badgeTint, String glyphText,
                     String titleText, String detailText, boolean active, int heightDp) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipToPadding(false);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        int padH = dp(context, 16);
        int padV = dp(context, 12);
        box.setPadding(padH, padV, padH, padV);

        TextView badge = label(context, badgeText, 11f, badgeTint, true);
        badge.setLetterSpacing(0.16f);
        box.addView(badge);

        TextView glyph = label(context, glyphText, 26f, active ? ACCENT : TEXT_FAINT, true);
        glyph.setPadding(0, dp(context, 4), 0, dp(context, 2));
        box.addView(glyph);

        TextView title = label(context, titleText, 17f, active ? 0xFFFFFFFF : 0xFFB9C6E0, true);
        title.setMaxLines(2);
        box.addView(title);

        TextView detail = label(context, detailText, 11.5f, TEXT_MUTED, false);
        detail.setLineSpacing(dp(context, 1), 1f);
        detail.setPadding(0, dp(context, 5), 0, 0);
        detail.setMaxLines(3);
        box.addView(detail);

        if (heightDp > 0) {
            int h = dp(context, heightDp);
            box.setMinimumHeight(h);
            box.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, h));
        }

        applyCardBackground(box, false, active);
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                applyCardBackground((LinearLayout) v, hasFocus, active);
                float scale = hasFocus ? 1.04f : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(120L).start();
                v.setElevation(hasFocus ? dp(v.getContext(), 12) : 0f);
            }
        });
        return new Card(box, badge, glyph, title, detail);
    }

    private static void applyCardBackground(LinearLayout box, boolean focused, boolean active) {
        Context context = box.getContext();
        float radius = dp(context, 14);
        if (focused) {
            box.setBackground(rounded(0x2E33DDBB, dp(context, 3), ACCENT, radius));
        } else if (active) {
            box.setBackground(rounded(CARD_FILL, dp(context, 1), CARD_STROKE, radius));
        } else {
            box.setBackground(rounded(0x0AFFFFFF, dp(context, 1), 0x14FFFFFF, radius));
        }
    }

    /** Fixed-size card layout: {@code weight} lets three cards fill a 960dp or a 1920dp screen. */
    static LinearLayout.LayoutParams cardParams(Context context, float weight, int leftMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        lp.leftMargin = dp(context, leftMarginDp);
        return lp;
    }

    /**
     * A compact horizontal card for the action row: glyph on the left, badge / title / one or two
     * lines of explanation on the right. Fixed height so a row of three lines up.
     */
    static Card cardRow(Context context, String badgeText, int badgeTint, String glyphText,
                        String titleText, String detailText, int heightDp) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        box.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));

        TextView glyph = label(context, glyphText, 24f, ACCENT, true);
        glyph.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(dp(context, 40), dp(context, 40));
        glyphLp.rightMargin = dp(context, 16);
        glyph.setLayoutParams(glyphLp);
        box.addView(glyph);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        box.addView(column, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = label(context, badgeText, 10f, badgeTint, true);
        badge.setLetterSpacing(0.16f);
        column.addView(badge);

        TextView title = label(context, titleText, 16f, 0xFFFFFFFF, true);
        column.addView(title);

        TextView detail = label(context, detailText, 11f, TEXT_MUTED, false);
        detail.setMaxLines(2);
        detail.setPadding(0, dp(context, 3), 0, 0);
        column.addView(detail);

        int h = dp(context, heightDp);
        box.setMinimumHeight(h);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h));

        applyCardBackground(box, false, true);
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                applyCardBackground((LinearLayout) v, hasFocus, true);
                v.animate().scaleX(hasFocus ? 1.02f : 1f).scaleY(hasFocus ? 1.02f : 1f)
                        .setDuration(120L).start();
                v.setElevation(hasFocus ? dp(v.getContext(), 12) : 0f);
            }
        });
        return new Card(box, badge, glyph, title, detail);
    }

    static TextView pill(Context context, String text, int tint) {
        TextView v = label(context, text, 11f, tint, true);
        v.setLetterSpacing(0.16f);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }
}
