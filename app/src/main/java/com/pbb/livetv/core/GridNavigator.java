package com.pbb.livetv.core;

/**
 * Pure D-pad navigation for the 2x2 CCTV grid. No Android types, so it is unit-testable.
 *
 * <p>Rules: LEFT/RIGHT/UP/DOWN move one cell; moving off the edge does nothing (the caller decides
 * whether that means "go to the nav bar" or "switch page"); a move into an empty cell is skipped in
 * the same direction so a page with 3 cameras still feels solid.
 */
public final class GridNavigator {

    public enum Move {
        NONE,
        LEFT,
        RIGHT,
        UP,
        DOWN
    }

    public static final int COLUMNS = 2;
    public static final int ROWS = 2;
    public static final int SLOTS = COLUMNS * ROWS;
    public static final int EDGE = -1;

    private GridNavigator() {
    }

    public static int column(int slot) {
        return slot % COLUMNS;
    }

    public static int row(int slot) {
        return slot / COLUMNS;
    }

    /**
     * @param occupied per-slot occupancy, length 4; null counts as empty
     * @return the slot focus should move to, or {@link #EDGE} when the move leaves the grid
     */
    public static int next(int slot, Move move, boolean[] occupied) {
        if (slot < 0 || slot >= SLOTS || move == null || move == Move.NONE) {
            return EDGE;
        }
        int col = column(slot);
        int r = row(slot);
        int colStep = move == Move.LEFT ? -1 : move == Move.RIGHT ? 1 : 0;
        int rowStep = move == Move.UP ? -1 : move == Move.DOWN ? 1 : 0;
        if (colStep != 0 && (col + colStep < 0 || col + colStep >= COLUMNS)) {
            return EDGE;
        }
        if (rowStep != 0 && (r + rowStep < 0 || r + rowStep >= ROWS)) {
            return EDGE;
        }
        int target = (r + rowStep) * COLUMNS + (col + colStep);
        if (isOccupied(occupied, target)) {
            return target;
        }
        // Skip over holes in the same direction (e.g. 3-camera page).
        int cursor = target;
        while (true) {
            int c = column(cursor) + colStep;
            int rr = row(cursor) + rowStep;
            if (colStep != 0 && (c < 0 || c >= COLUMNS)) {
                return EDGE;
            }
            if (rowStep != 0 && (rr < 0 || rr >= ROWS)) {
                return EDGE;
            }
            cursor = rr * COLUMNS + c;
            if (isOccupied(occupied, cursor)) {
                return cursor;
            }
        }
    }

    public static boolean isOccupied(boolean[] occupied, int slot) {
        return occupied != null && slot >= 0 && slot < occupied.length && occupied[slot];
    }

    public static boolean[] occupancy(int cameraCount) {
        boolean[] occupied = new boolean[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            occupied[i] = i < cameraCount;
        }
        return occupied;
    }

    /** Total pages needed for {@code cameraCount} cameras, at least 1. */
    public static int pageCount(int cameraCount) {
        if (cameraCount <= 0) {
            return 1;
        }
        return (cameraCount + SLOTS - 1) / SLOTS;
    }

    /** Clamp a page index into range. */
    public static int clampPage(int page, int cameraCount) {
        return Math.max(0, Math.min(page, pageCount(cameraCount) - 1));
    }

    /** First occupied slot on a page, or 0 when the page is empty. */
    public static int firstOccupied(boolean[] occupied) {
        if (occupied == null) {
            return 0;
        }
        for (int i = 0; i < occupied.length; i++) {
            if (occupied[i]) {
                return i;
            }
        }
        return 0;
    }
}
