package com.pbb.livetv.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pbb.livetv.Camera;

/**
 * Bottom navigation with exactly four sections.
 *
 * <p>TV-specific behaviour: the bar is only focusable when the user explicitly moves down into it,
 * LEFT/RIGHT change section while it has focus, and UP returns to the content area. When it does not
 * have focus a selected section stays highlighted (accent bar + brighter label) so the viewer always
 * knows where they are. Nothing here relies on touch.
 */
public final class NavBar extends LinearLayout {

    public interface Listener {
        void onSectionSelected(Camera.Section section);

        void onFocusChanged(boolean focused);
    }

    private final Camera.Section[] sections = Camera.Section.values();
    private final FrameLayout[] cells = new FrameLayout[sections.length];
    private final TextView[] labels = new TextView[sections.length];
    private final View[] underlines = new View[sections.length];

    private Listener listener;
    private Camera.Section selected = Camera.Section.MAIN_LIVE;
    private int focusedIndex = 0;

    public NavBar(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(Theme.PANEL);
        int padH = Theme.dp(context, 12);
        setPadding(padH, Theme.dp(context, 6), padH, Theme.dp(context, 6));

        for (Camera.Section section : sections) {
            int index = section.index;
            FrameLayout cell = new FrameLayout(context);
            cell.setFocusable(true);
            cell.setFocusableInTouchMode(false);
            cell.setClickable(true);

            LinearLayout content = UiKit.column(context);
            content.setGravity(Gravity.CENTER);
            content.setPadding(Theme.dp(context, 26), Theme.dp(context, 8),
                    Theme.dp(context, 26), Theme.dp(context, 6));

            TextView label = UiKit.caption(context, section.label, 13f, Theme.TEXT_SECOND);
            label.setGravity(Gravity.CENTER);
            content.addView(label);

            View underline = new View(context);
            LinearLayout.LayoutParams ulLp = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(context, 3));
            ulLp.topMargin = Theme.dp(context, 6);
            underline.setBackground(Theme.rounded(context, Color.TRANSPARENT, 2));
            content.addView(underline, ulLp);

            cell.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            cell.setOnFocusChangeListener((v, hasFocus) -> {
                focusedIndex = index;
                paint(index, hasFocus);
                if (listener != null) {
                    listener.onFocusChanged(hasFocus);
                }
            });
            cell.setOnClickListener(v -> {
                selected = sections[index];
                paintAll();
                if (listener != null) {
                    listener.onSectionSelected(sections[index]);
                }
            });
            cell.setOnKeyListener((v, keyCode, event) -> false);

            LinearLayout.LayoutParams cellLp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cellLp.rightMargin = Theme.dp(context, 4);
            addView(cell, cellLp);
            cells[index] = cell;
            labels[index] = label;
            underlines[index] = underline;
        }

        setSelected(Camera.Section.MAIN_LIVE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setSelected(Camera.Section section) {
        selected = section;
        paintAll();
    }

    public int focusedIndex() {
        return focusedIndex;
    }

    /** True when one of the four section cells currently holds focus. */
    public boolean hasNavFocus() {
        for (FrameLayout cell : cells) {
            if (cell != null && cell.hasFocus()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        // Own the horizontal axis here: whatever the focus-search does, LEFT/RIGHT on the bar must
        // change section, never jump into the content area.
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && hasNavFocus()) {
            int key = event.getKeyCode();
            if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                int delta = key == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                int target = Math.max(0, Math.min(sections.length - 1, focusedIndex + delta));
                if (target != focusedIndex) {
                    cells[target].requestFocus();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    public void focusSection(Camera.Section section) {
        View cell = cells[section.index];
        if (cell != null) {
            cell.requestFocus();
        }
    }

    private void paintAll() {
        for (int i = 0; i < cells.length; i++) {
            paint(i, cells[i].hasFocus());
        }
    }

    private void paint(int index, boolean focused) {
        boolean isSelected = sections[index] == selected;
        TextView label = labels[index];
        View underline = underlines[index];
        if (focused) {
            label.setTextColor(Theme.TEXT);
            label.setTypeface(Theme.medium);
            cells[index].setBackground(Theme.outlined(getContext(), 0x1A33DDBB, Theme.ACCENT, 2, 6));
            underline.setBackground(Theme.rounded(getContext(), Theme.ACCENT, 2));
            underline.setAlpha(1f);
            cells[index].animate().scaleX(1.04f).scaleY(1.04f).setDuration(120L).start();
        } else {
            label.setTextColor(isSelected ? Theme.ACCENT : Theme.TEXT_SECOND);
            cells[index].setBackground(Theme.rounded(getContext(), Color.TRANSPARENT, 6));
            underline.setBackground(Theme.rounded(getContext(),
                    isSelected ? Theme.ACCENT : Color.TRANSPARENT, 2));
            underline.setAlpha(isSelected ? 1f : 0f);
            cells[index].animate().scaleX(1f).scaleY(1f).setDuration(120L).start();
        }
    }

    /** Called by screens that want the nav to look inactive while content is focused. */
    public void setDimmed(boolean dimmed) {
        animate().alpha(dimmed ? 0.86f : 1f).setDuration(150L).start();
    }

    /** Height the activity should reserve for the bar. */
    public static int heightPx(Context context) {
        return Theme.dp(context, 66);
    }
}
