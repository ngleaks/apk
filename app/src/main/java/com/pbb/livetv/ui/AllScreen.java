package com.pbb.livetv.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pbb.livetv.Camera;
import com.pbb.livetv.core.GridNavigator;

import java.util.ArrayList;
import java.util.List;

/**
 * ALL – the CCTV multi-view.
 *
 * <p>2x2 grid by default, driven entirely by the D-pad: LEFT/RIGHT/UP/DOWN move the focus between
 * tiles, OK opens the focused camera fullscreen, BACK leaves fullscreen and restores the previous
 * tile. Additional cameras are paged (2x2, 4 per page) with a page indicator; the layout adapts to
 * 1, 2 or 3 cameras instead of leaving holes.
 */
public final class AllScreen extends FrameLayout {

    public interface Host {
        void onOpenCamera(Camera camera);

        void onFocusContent();
    }

    public static final int COLUMNS = GridNavigator.COLUMNS;
    public static final int ROWS = GridNavigator.ROWS;
    public static final int PER_PAGE = GridNavigator.SLOTS;
    /** Hard ceiling on simultaneous decoders – four 1080p streams is what a TV box can sustain. */
    public static final int MAX_LIVE_STREAMS = GridNavigator.SLOTS;

    private final LinearLayout column;
    private final TextView title;
    private final TextView subtitle;
    private final TextView pager;
    private final FrameLayout grid;
    private final TileView[] tiles = new TileView[PER_PAGE];

    private Host host;
    private List<Camera> cameras = new ArrayList<>();
    private int page = 0;
    private int focusedSlot = 0;

    public AllScreen(Context context) {
        super(context);

        column = UiKit.column(context);
        column.setBackgroundColor(Theme.BG);
        addView(column, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout header = UiKit.row(context);
        header.setPadding(Theme.dp(context, Theme.GUTTER), Theme.dp(context, 18),
                Theme.dp(context, Theme.GUTTER), Theme.dp(context, 8));
        LinearLayout titles = UiKit.column(context);
        title = UiKit.heading(context, "ALL CAMERAS", 20f, Theme.TEXT);
        title.setLetterSpacing(0.06f);
        titles.addView(title);
        subtitle = UiKit.label(context, "CCTV multi-view · 4 cameras on screen", 13f, Theme.TEXT_SECOND);
        titles.addView(subtitle);
        header.addView(titles, UiKit.lp(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 1f));

        pager = UiKit.caption(context, "PAGE 1 / 1", 11f, Theme.TEXT_SECOND);
        pager.setBackground(Theme.outlined(context, 0x14FFFFFF, Theme.BORDER, 1, 4));
        pager.setPadding(Theme.dp(context, 10), Theme.dp(context, 5),
                Theme.dp(context, 10), Theme.dp(context, 5));
        header.addView(pager);
        column.addView(header);

        grid = new FrameLayout(context);
        grid.setPadding(Theme.dp(context, Theme.GUTTER - 6), Theme.dp(context, 6),
                Theme.dp(context, Theme.GUTTER - 6), Theme.dp(context, 6));
        column.addView(grid, UiKit.lp(LayoutParams.MATCH_PARENT, 0, 1f));

        // Pre-create four 16:9 tiles in a manual 2x2 layout (no nested weights => predictable focus).
        for (int i = 0; i < PER_PAGE; i++) {
            final int slot = i;
            TileView tile = new TileView(context, true);
            tile.setOnClickListener(v -> {
                Camera camera = cameraAt(slot);
                if (camera != null && host != null) {
                    host.onOpenCamera(camera);
                }
            });
            tile.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    focusedSlot = slot;
                    if (host != null) {
                        host.onFocusContent();
                    }
                }
            });
            tiles[i] = tile;
            grid.addView(tile);
        }
    }

    public void setHost(Host host) {
        this.host = host;
    }

    public void bind(List<Camera> all, Camera active) {
        cameras = all == null ? new ArrayList<>() : all;
        int pages = pageCount();
        int activeIndex = cameras.indexOf(active);
        if (activeIndex >= 0) {
            page = Math.min(pages - 1, activeIndex / PER_PAGE);
        }
        page = Math.max(0, Math.min(page, pages - 1));
        subtitle.setText(cameras.size() == 0
                ? "No cameras configured"
                : cameras.size() + (cameras.size() == 1 ? " camera · " : " cameras · ")
                + "2×2 CCTV view");
        pager.setText("PAGE " + (page + 1) + " / " + pages);
        int activeSlot = activeIndex >= 0 ? activeIndex % PER_PAGE : 0;
        if (activeIndex >= 0 && cameraAt(activeSlot) != null) {
            focusedSlot = activeSlot;
        }
        bindPage(active);
    }

    private void bindPage(Camera active) {
        for (int slot = 0; slot < PER_PAGE; slot++) {
            Camera camera = cameraAt(slot);
            TileView tile = tiles[slot];
            tile.setVisibility(camera == null ? View.INVISIBLE : View.VISIBLE);
            if (camera != null) {
                tile.bind(camera);
                tile.setSelectedMark(active != null && camera.id.equals(active.id));
            }
        }
        grid.requestLayout();
    }

    public int pageCount() {
        return Math.max(1, (int) Math.ceil(cameras.size() / (double) PER_PAGE));
    }

    public int page() {
        return page;
    }

    public void setPage(int newPage, Camera active) {
        page = GridNavigator.clampPage(newPage, cameras.size());
        pager.setText("PAGE " + (page + 1) + " / " + pageCount());
        int activeIndex = cameras.indexOf(active);
        int activeSlot = activeIndex >= 0 ? activeIndex % PER_PAGE : 0;
        if (activeIndex >= 0 && cameraAt(activeSlot) != null) {
            focusedSlot = activeSlot;
        }
        bindPage(active);
        focusSlot(focusedSlot);
    }

    /** Page the active camera lives on. */
    public int pageOf(Camera camera) {
        int index = cameras.indexOf(camera);
        return index < 0 ? 0 : index / PER_PAGE;
    }

    public Camera cameraAt(int slot) {
        int index = page * PER_PAGE + slot;
        return index >= 0 && index < cameras.size() ? cameras.get(index) : null;
    }

    public TileView tileAt(int slot) {
        return tiles[slot];
    }

    public TileView tileFor(String cameraId) {
        for (int slot = 0; slot < PER_PAGE; slot++) {
            Camera camera = cameraAt(slot);
            if (camera != null && camera.id.equals(cameraId)) {
                return tiles[slot];
            }
        }
        return null;
    }

    public List<TileView> visibleTiles() {
        List<TileView> out = new ArrayList<>();
        for (int slot = 0; slot < PER_PAGE; slot++) {
            if (cameraAt(slot) != null) {
                out.add(tiles[slot]);
            }
        }
        return out;
    }

    public void focusSlot(int slot) {
        int target = Math.max(0, Math.min(slot, PER_PAGE - 1));
        if (cameraAt(target) == null) {
            // fall back to the first occupied slot on this page
            for (int i = 0; i < PER_PAGE; i++) {
                if (cameraAt(i) != null) {
                    target = i;
                    break;
                }
            }
        }
        focusedSlot = target;
        tiles[target].requestFocus();
    }

    public int focusedSlot() {
        return focusedSlot;
    }

    public View focusTarget() {
        return tiles[focusedSlot];
    }

    /** D-pad navigation between tiles; returns true when focus moved. */
    public boolean moveFocus(int keyCode) {
        GridNavigator.Move move;
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                move = GridNavigator.Move.LEFT;
                break;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                move = GridNavigator.Move.RIGHT;
                break;
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                move = GridNavigator.Move.UP;
                break;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                move = GridNavigator.Move.DOWN;
                break;
            default:
                return false;
        }
        int target = GridNavigator.next(focusedSlot, move, occupancy());
        if (target == GridNavigator.EDGE) {
            return false;
        }
        focusedSlot = target;
        tiles[target].requestFocus();
        return true;
    }

    /** Occupancy of the slots on the current page. */
    public boolean[] occupancy() {
        boolean[] occupied = new boolean[GridNavigator.SLOTS];
        for (int slot = 0; slot < PER_PAGE; slot++) {
            occupied[slot] = cameraAt(slot) != null;
        }
        return occupied;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        layoutGrid();
    }

    /** Lays the four tiles out manually so each keeps 16:9 and gaps stay equal. */
    private void layoutGrid() {
        int width = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        int height = grid.getHeight() - grid.getPaddingTop() - grid.getPaddingBottom();
        if (width <= 0 || height <= 0) {
            return;
        }
        int gap = Theme.dp(getContext(), Theme.GAP);
        TileLayout.Box cell = TileLayout.cell(width, height, COLUMNS, ROWS, gap);
        TileLayout.Box video = TileLayout.fit16x9(cell.width, cell.height);
        for (int slot = 0; slot < PER_PAGE; slot++) {
            TileView tile = tiles[slot];
            int column = slot % COLUMNS;
            int row = slot / COLUMNS;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(video.width, video.height);
            lp.leftMargin = grid.getPaddingLeft() + column * (cell.width + gap)
                    + TileLayout.offset(cell.width, video.width);
            lp.topMargin = grid.getPaddingTop() + row * (cell.height + gap)
                    + TileLayout.offset(cell.height, video.height);
            tile.setLayoutParams(lp);
        }
    }

    public void requestContentFocus() {
        focusSlot(focusedSlot);
    }

    public TextView pagerView() {
        return pager;
    }

    public View gridView() {
        return grid;
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
    }

    public void setGravityTop() {
        column.setGravity(Gravity.TOP);
    }
}
