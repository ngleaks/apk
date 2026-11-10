package com.pbb.livetv.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pbb.livetv.Camera;

import java.util.List;

/**
 * A single-camera section (MAIN LIVE / DINING / EXTERNAL).
 *
 * <p>Layout: a big 16:9 player that keeps its aspect ratio, a slim header with the section name and
 * state, a "cameras in this section" strip when there is more than one feed, and the bottom nav.
 * The player surface itself is focusable so OK opens the controls and DOWN reaches the nav bar.
 */
public final class CameraScreen extends FrameLayout {

    public interface Host {
        void onRetry();

        void onSelectCamera(Camera camera);

        void onFocusContent();
    }

    private final FrameLayout stage;
    private final TileView tile;
    private final LinearLayout chips;
    private final LinearLayout chipRow;
    private final TextView headerTitle;
    private final TextView headerMeta;
    private Camera.Section section;
    private Host host;

    public CameraScreen(Context context, Camera.Section section) {
        super(context);
        this.section = section;

        LinearLayout column = UiKit.column(context);
        column.setBackgroundColor(Theme.BG);
        addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------------------------------------------------------------- header
        LinearLayout header = UiKit.row(context);
        header.setPadding(Theme.dp(context, Theme.GUTTER), Theme.dp(context, 18),
                Theme.dp(context, Theme.GUTTER), Theme.dp(context, 8));

        LinearLayout titles = UiKit.column(context);
        headerTitle = UiKit.heading(context, section.label, 20f, Theme.TEXT);
        headerTitle.setLetterSpacing(0.06f);
        titles.addView(headerTitle);
        headerMeta = UiKit.label(context, section.title, 13f, Theme.TEXT_SECOND);
        titles.addView(headerMeta);
        header.addView(titles, UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = UiKit.caption(context, "LIVE STUDIO", 10f, Theme.ACCENT);
        badge.setBackground(Theme.outlined(context, 0x1433DDBB, Theme.ACCENT_DIM, 1, 4));
        badge.setPadding(Theme.dp(context, 10), Theme.dp(context, 5),
                Theme.dp(context, 10), Theme.dp(context, 5));
        header.addView(badge);
        column.addView(header);

        // ---------------------------------------------------------------- camera strip (above video)
        stage = new FrameLayout(context);
        final TileView playerTile = new TileView(context, false);
        this.tile = playerTile;
        stage.addView(playerTile, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        playerTile.setOnClickListener(v -> {
            if (host != null) {
                host.onFocusContent();
            }
        });
        playerTile.setFocusable(true);
        playerTile.setFocusableInTouchMode(false);

        LinearLayout.LayoutParams stageLp = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        stageLp.leftMargin = Theme.dp(context, Theme.GUTTER);
        stageLp.rightMargin = Theme.dp(context, Theme.GUTTER);
        int pad = Theme.dp(context, 12);
        stage.setPadding(pad, pad, pad, pad);
        stage.setBackground(Theme.rounded(context, Theme.TILE, Theme.RADIUS));
        // (stage is added after the strip below)

        // ---------------------------------------------------------------- camera strip
        chips = UiKit.column(context);
        chipRow = UiKit.row(context);
        chipRow.setPadding(Theme.dp(context, Theme.GUTTER), 0,
                Theme.dp(context, Theme.GUTTER), Theme.dp(context, 10));
        chipRow.setFocusable(false);
        chips.addView(chipRow);
        chips.setVisibility(GONE);
    }

    public void setHost(Host host) {
        this.host = host;
    }

    public TileView tile() {
        return tile;
    }

    public FrameLayout stage() {
        return stage;
    }

    public View focusTarget() {
        return tile;
    }

    public void bind(List<Camera> sectionCameras, Camera active) {
        headerMeta.setText(active != null ? active.subtitle : section.title);
        tile.bind(active);
        rebuildChips(sectionCameras, active);
    }

    private void rebuildChips(List<Camera> sectionCameras, Camera active) {
        chipRow.removeAllViews();
        if (sectionCameras == null || sectionCameras.size() <= 1) {
            chips.setVisibility(GONE);
            return;
        }
        chips.setVisibility(VISIBLE);
        TextView caption = UiKit.caption(getContext(), "CAMERAS", 10f, Theme.TEXT_MUTED);
        LinearLayout.LayoutParams capLp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        capLp.rightMargin = Theme.dp(getContext(), 14);
        chipRow.addView(caption, capLp);
        for (final Camera camera : sectionCameras) {
            boolean isActive = active != null && camera.id.equals(active.id);
            TextView chip = UiKit.heading(getContext(), camera.label, 13f,
                    isActive ? Theme.ACCENT : Theme.TEXT_SECOND);
            chip.setFocusable(true);
            chip.setFocusableInTouchMode(false);
            chip.setClickable(true);
            chip.setPadding(Theme.dp(getContext(), 14), Theme.dp(getContext(), 8),
                    Theme.dp(getContext(), 14), Theme.dp(getContext(), 8));
            chip.setBackground(isActive
                    ? Theme.outlined(getContext(), 0x2233DDBB, Theme.ACCENT_DIM, 1, 5)
                    : Theme.outlined(getContext(), 0x0FFFFFFF, Theme.BORDER, 1, 5));
            chip.setOnFocusChangeListener((v, hasFocus) -> {
                v.setBackground(hasFocus
                        ? Theme.outlined(getContext(), 0x2233DDBB, Theme.ACCENT, 2, 5)
                        : isActive
                        ? Theme.outlined(getContext(), 0x2233DDBB, Theme.ACCENT_DIM, 1, 5)
                        : Theme.outlined(getContext(), 0x0FFFFFFF, Theme.BORDER, 1, 5));
                ((TextView) v).setTextColor(hasFocus || isActive ? Theme.TEXT : Theme.TEXT_SECOND);
            });
            chip.setOnClickListener(v -> {
                if (host != null) {
                    host.onSelectCamera(camera);
                }
            });
            LinearLayout.LayoutParams lp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Theme.dp(getContext(), 8);
            chipRow.addView(chip, lp);
        }
    }

    /** Keeps the 16:9 player centred and letterboxed inside the available stage. */
    public void layoutStage(int availableWidth, int availableHeight) {
        if (availableWidth <= 0 || availableHeight <= 0) {
            return;
        }
        TileLayout.Box box = TileLayout.fit16x9(availableWidth, availableHeight);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(box.width, box.height, Gravity.CENTER);
        tile.setLayoutParams(lp);
    }

    public Camera.Section section() {
        return section;
    }

    /** One CameraScreen is reused for all three single-camera sections; retitle it on change. */
    public void setSection(Camera.Section newSection) {
        this.section = newSection;
        headerTitle.setText(newSection.label);
        headerMeta.setText(newSection.title);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
    }

    public void requestContentFocus() {
        tile.requestFocus();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (stage != null) {
            stage.post(() -> layoutStage(stage.getWidth() - stage.getPaddingLeft() - stage.getPaddingRight(),
                    stage.getHeight() - stage.getPaddingTop() - stage.getPaddingBottom()));
        }
    }
}
