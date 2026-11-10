package com.pbb.livetv.core;

import android.content.Context;

import com.pbb.livetv.Camera;
import com.pbb.livetv.CameraCatalog;
import com.pbb.livetv.core.GridNavigator;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for "what is on screen". Lives in the Activity, not a static singleton, so
 * rotation/recreate and process death cannot leave stale players or listeners behind.
 */
public final class AppState {

    public interface Listener {
        /** Catalogue changed (remote override merged, deep link added a camera). */
        void onCatalogChanged();

        void onSectionChanged(Camera.Section section);
    }

    private final Context context;
    private final List<Listener> listeners = new ArrayList<>();
    private List<Camera> cameras = new ArrayList<>();
    private Camera.Section section = Camera.Section.MAIN_LIVE;
    private final String[] selection = new String[Camera.Section.values().length];

    public AppState(Context context) {
        this.context = context.getApplicationContext();
        this.cameras = CameraCatalog.load(this.context);
        String lastId = CameraCatalog.lastCameraId(this.context);
        for (Camera.Section s : Camera.Section.values()) {
            selection[s.index] = s == Camera.Section.ALL ? lastId : null;
        }
        this.section = CameraCatalog.lastSection(this.context, Camera.Section.MAIN_LIVE);
    }

    public List<Camera> cameras() {
        return cameras;
    }

    /**
     * Cameras for a section. ALL is intentionally the union of every section: the multi-view needs
     * every feed, which is exactly what a CCTV wall shows.
     */
    public List<Camera> camerasIn(Camera.Section target) {
        if (target == Camera.Section.ALL) {
            return new ArrayList<>(cameras);
        }
        return Camera.ofSection(cameras, target);
    }

    public Camera.Section section() {
        return section;
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void selectSection(Camera.Section newSection) {
        if (newSection == null || newSection == section) {
            return;
        }
        section = newSection;
        CameraCatalog.rememberSection(context, section);
        for (Listener l : new ArrayList<>(listeners)) {
            l.onSectionChanged(section);
        }
    }

    public void replaceCatalog(List<Camera> fresh) {
        if (fresh == null || fresh.isEmpty()) {
            return;
        }
        cameras = fresh;
        for (Listener l : new ArrayList<>(listeners)) {
            l.onCatalogChanged();
        }
    }

    /** Last camera focused in a grid-ish section, or the first playable one. */
    public Camera selected(Camera.Section target) {
        List<Camera> pool = camerasIn(target);
        String id = selection[target.index];
        Camera remembered = Camera.byId(pool, id);
        if (remembered != null) {
            return remembered;
        }
        Camera first = Camera.firstPlayable(pool);
        return first != null ? first : (pool.isEmpty() ? null : pool.get(0));
    }

    public int selectedIndex(Camera.Section target) {
        List<Camera> pool = camerasIn(target);
        Camera current = selected(target);
        int index = pool.indexOf(current);
        return Math.max(0, index);
    }

    public void rememberSelection(Camera.Section target, Camera camera) {
        if (camera == null) {
            return;
        }
        selection[target.index] = camera.id;
        CameraCatalog.rememberCamera(context, camera.id);
    }

    public Camera byId(String id) {
        return Camera.byId(cameras, id);
    }
}
