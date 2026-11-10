package com.pbb.livetv.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Playback control overlay: hidden while watching, shown on OK, auto-hides after a few idle seconds.
 *
 * <p>Controls are big, D-pad only, and never trap focus: LEFT/RIGHT move between actions, BACK
 * closes the overlay, DOWN goes to the section bar, and it hides itself when idle so the picture
 * stays clean.
 */
public final class PlayerControls extends FrameLayout {

    public interface Listener {
        void onPlayPause();

        void onRetry();

        void onAudioToggle();

        void onClose();

        void onNextCamera();
    }

    public static final int ACTION_PLAY_PAUSE = 0;
    public static final int ACTION_AUDIO = 1;
    public static final int ACTION_NEXT = 2;
    public static final int ACTION_RETRY = 3;
    public static final int ACTION_CLOSE = 4;

    private final LinearLayout buttons;
    private final TextView titleView;
    private final TextView statusView;
    private final List<View> actions = new ArrayList<>();
    private Listener listener;
    private int focusedIndex = 0;

    public PlayerControls(Context context) {
        super(context);
        setVisibility(GONE);
        setBackgroundColor(Theme.SCRIM);
        setClickable(true);

        LinearLayout panel = UiKit.column(context);
        panel.setBackground(Theme.outlined(context, 0xF00B0F17, Theme.BORDER, 1, Theme.RADIUS));
        int pad = Theme.dp(context, 26);
        panel.setPadding(pad, Theme.dp(context, 20), pad, Theme.dp(context, 18));

        titleView = UiKit.heading(context, "", 24f, Theme.TEXT);
        panel.addView(titleView);

        statusView = UiKit.label(context, "", 13f, Theme.TEXT_SECOND);
        LinearLayout.LayoutParams stLp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = Theme.dp(context, 4);
        panel.addView(statusView, stLp);

        buttons = UiKit.row(context);
        LinearLayout.LayoutParams btLp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btLp.topMargin = Theme.dp(context, 18);
        panel.addView(buttons, btLp);

        LayoutParams panelParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(panel, panelParams);

        TextView hint = UiKit.caption(context, "OK  activate   ·   ◀ ▶  move   ·   BACK  close", 10f,
                Theme.TEXT_MUTED);
        hint.setGravity(Gravity.CENTER);
        LayoutParams hintParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintParams.bottomMargin = Theme.dp(context, 96);
        addView(hint, hintParams);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Rebuild action buttons so labels reflect the actual state (Pause vs Play, Mute vs Sound). */
    public void configure(String title, String status, String[] actionLabels, boolean[] enabled) {
        titleView.setText(title);
        statusView.setText(status);
        buttons.removeAllViews();
        actions.clear();
        focusedIndex = 0;
        for (int i = 0; i < actionLabels.length; i++) {
            final int index = i;
            boolean isEnabled = enabled == null || i >= enabled.length || enabled[i];
            View button = buildButton(actionLabels[i], isEnabled);
            button.setOnFocusChangeListener((v, hasFocus) -> {
                focusedIndex = index;
                paint(v, hasFocus, isEnabled);
            });
            button.setEnabled(isEnabled);
            button.setOnClickListener(v -> activate(index));
            LinearLayout.LayoutParams lp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Theme.dp(getContext(), 10);
            buttons.addView(button, lp);
            actions.add(button);
            paint(button, false, isEnabled);
        }
    }

    private View buildButton(String label, boolean enabled) {
        TextView v = UiKit.heading(getContext(), label, 15f, Theme.TEXT);
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setFocusableInTouchMode(false);
        v.setClickable(true);
        v.setPadding(Theme.dp(getContext(), 22), Theme.dp(getContext(), 11),
                Theme.dp(getContext(), 22), Theme.dp(getContext(), 11));
        return v;
    }

    private void paint(View view, boolean focused, boolean enabled) {
        Context context = getContext();
        android.graphics.drawable.Drawable bg = focused
                ? Theme.outlined(context, Theme.ACCENT, Theme.ACCENT, 2, 6)
                : Theme.outlined(context, 0x1AFFFFFF, Theme.BORDER, 1, 6);
        view.setBackground(bg);
        ((TextView) view).setTextColor(!enabled ? Theme.TEXT_MUTED
                : focused ? Color0.BLACK : Theme.TEXT);
        view.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                .setDuration(120L).start();
    }

    private void activate(int index) {
        if (listener == null) {
            return;
        }
        switch (index) {
            case ACTION_PLAY_PAUSE:
                listener.onPlayPause();
                break;
            case ACTION_AUDIO:
                listener.onAudioToggle();
                break;
            case ACTION_NEXT:
                listener.onNextCamera();
                break;
            case ACTION_RETRY:
                listener.onRetry();
                break;
            default:
                listener.onClose();
                break;
        }
    }

    public void showAndFocus() {
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(150L).start();
        if (!actions.isEmpty()) {
            actions.get(Math.min(focusedIndex, actions.size() - 1)).requestFocus();
        }
    }

    public void hide() {
        setVisibility(GONE);
    }

    public boolean isShown() {
        return getVisibility() == VISIBLE;
    }

    public boolean moveFocus(int direction) {
        if (actions.isEmpty()) {
            return false;
        }
        int next = focusedIndex + direction;
        if (next < 0 || next >= actions.size()) {
            return false;
        }
        actions.get(next).requestFocus();
        return true;
    }

    /** Tiny helper so the focused label colour stays readable on the accent fill. */
    private static final class Color0 {
        static final int BLACK = 0xFF04130F;
    }

    /** Used by the activity to keep the panel above the nav bar. */
    public static ViewGroup.LayoutParams fullscreenParams() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
