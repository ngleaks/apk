package com.pbb.livetv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One camera feed. Immutable value object; the whole UI is a function of a {@code List<Camera>}.
 *
 * <p>A camera is always in one of three states and the UI never has to invent one:
 * <ul>
 *     <li>{@link #playable()} – a real, authorised stream URL is configured and can be opened.</li>
 *     <li>{@link #drmsProtected} – a real stream exists but needs a ClearKey licence. Playback stays
 *         disabled until licence material is supplied at build time; we never ship keys.</li>
 *     <li>neither – no stream at all, so the camera renders as an honest "No stream configured"
 *         placeholder instead of a fake feed.</li>
 * </ul>
 */
public final class Camera {

    /** Sections of the app. Order matters: it is the bottom navigation order. */
    public enum Section {
        MAIN_LIVE(0, "MAIN LIVE", "Main live feed"),
        DINING(1, "DINING", "Dining feeds"),
        EXTERNAL(2, "EXTERNAL", "External feeds"),
        ALL(3, "ALL", "All cameras");

        public final int index;
        public final String label;
        public final String title;

        Section(int index, String label, String title) {
            this.index = index;
            this.label = label;
            this.title = title;
        }

        public static Section fromName(String name, Section fallback) {
            if (name == null) {
                return fallback;
            }
            for (Section s : values()) {
                if (s.name().equalsIgnoreCase(name.trim())) {
                    return s;
                }
            }
            return fallback;
        }

        public static Section fromIndex(int index) {
            for (Section s : values()) {
                if (s.index == index) {
                    return s;
                }
            }
            return MAIN_LIVE;
        }
    }

    public final String id;
    public final String label;
    public final String subtitle;
    public final Section section;
    public final String url;
    public final String format;
    /** Key id / key value pair as published by the provider, empty when not DRM protected. */
    public final String keyId;
    public final String keyValue;
    /** 1-based position used for labels and for restoring the last selected tile. */
    public final int order;

    public Camera(String id, String label, String subtitle, Section section, String url,
           String format, String keyId, String keyValue, int order) {
        this.id = id == null ? "" : id;
        this.label = label == null || label.isEmpty() ? "Camera " + order : label;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.section = section == null ? Section.ALL : section;
        this.url = url == null ? "" : url.trim();
        this.format = format == null || format.isEmpty() ? StreamFormat.guess(this.url) : format;
        this.keyId = keyId == null ? "" : keyId.trim();
        this.keyValue = keyValue == null ? "" : keyValue.trim();
        this.order = order;
    }

    public boolean hasUrl() {
        return !url.isEmpty();
    }

    /** ClearKey material present, so DRM playback is actually possible. */
    public boolean hasKeys() {
        return !keyId.isEmpty() && !keyValue.isEmpty();
    }

    public boolean drmsProtected() {
        return hasUrl() && !keyId.isEmpty() && !keyValue.isEmpty();
    }

    /** A real stream that is waiting for licence material. UI shows "DRM key required". */
    public boolean awaitingKey() {
        return hasUrl() && !hasKeys() && StreamFormat.isDash(url);
    }

    public boolean playable() {
        return hasUrl() && !awaitingKey();
    }

    public String statusText() {
        if (!hasUrl()) {
            return "No stream";
        }
        if (awaitingKey()) {
            return "Licence required";
        }
        return StreamFormat.label(format);
    }

    public String footerText() {
        if (!hasUrl()) {
            return "No stream configured";
        }
        if (awaitingKey()) {
            return "ClearKey licence not provided";
        }
        if (hasKeys()) {
            return "MPEG-DASH · ClearKey";
        }
        return StreamFormat.label(format) + " · live";
    }

    public Camera withKeys(String newKeyId, String newKeyValue) {
        return new Camera(id, label, subtitle, section, url, format, newKeyId, newKeyValue, order);
    }

    public Camera withUrl(String newUrl) {
        String fmt = StreamFormat.guess(newUrl);
        return new Camera(id, label, subtitle, section, newUrl, fmt, keyId, keyValue, order);
    }

    public static List<Camera> ofSection(List<Camera> all, Section section) {
        if (all == null) {
            return Collections.emptyList();
        }
        List<Camera> out = new ArrayList<>();
        for (Camera c : all) {
            if (c.section == section) {
                out.add(c);
            }
        }
        return out;
    }

    public static Camera firstPlayable(List<Camera> cameras) {
        if (cameras == null) {
            return null;
        }
        for (Camera c : cameras) {
            if (c.playable()) {
                return c;
            }
        }
        return null;
    }

    public static Camera byId(List<Camera> cameras, String id) {
        if (cameras == null || id == null) {
            return null;
        }
        for (Camera c : cameras) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "Camera{" + id + " " + label + " " + section + " " + statusText() + "}";
    }
}
