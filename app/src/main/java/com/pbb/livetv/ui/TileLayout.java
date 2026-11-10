package com.pbb.livetv.ui;

/**
 * Pure layout maths for the CCTV wall and the single-camera stage. Keeping this out of the views
 * means the aspect-ratio rules ("never stretch, always letterbox") are unit-testable.
 */
public final class TileLayout {

    private TileLayout() {
    }

    /** A rectangle in pixels. */
    public static final class Box {
        public final int width;
        public final int height;

        public Box(int width, int height) {
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }
    }

    /** Biggest 16:9 box that fits inside {@code width x height}. */
    public static Box fit16x9(int width, int height) {
        if (width <= 0 || height <= 0) {
            return new Box(0, 0);
        }
        int w = width;
        int h = Math.round(w * 9f / 16f);
        if (h > height) {
            h = height;
            w = Math.round(h * 16f / 9f);
        }
        return new Box(Math.min(w, width), h);
    }

    /** Cell size for a {@code columns x rows} grid with equal gaps, rounded down so nothing overflows. */
    public static Box cell(int availableWidth, int availableHeight, int columns, int rows, int gap) {
        if (availableWidth <= 0 || availableHeight <= 0 || columns <= 0 || rows <= 0) {
            return new Box(0, 0);
        }
        int usableW = availableWidth - gap * (columns - 1);
        int usableH = availableHeight - gap * (rows - 1);
        return new Box(Math.max(0, usableW / columns), Math.max(0, usableH / rows));
    }

    /** Top-left position of the 16:9 video inside a cell, centred. */
    public static int offset(int cellSize, int videoSize) {
        return Math.max(0, (cellSize - videoSize) / 2);
    }
}
