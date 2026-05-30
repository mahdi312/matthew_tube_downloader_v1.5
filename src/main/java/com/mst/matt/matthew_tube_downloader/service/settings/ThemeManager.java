package com.mst.matt.matthew_tube_downloader.service.settings;

import com.mst.matt.matthew_tube_downloader.HelloApplication;
import javafx.application.Platform;
import javafx.scene.Scene;

import java.lang.ref.WeakReference;

/**
 * v1.5: Centralized theme switching.
 *
 * Holds a weak reference to the active {@link Scene} so the Settings tab
 * can swap stylesheets at runtime without restarting the app.
 *
 * Three themes are shipped:
 *   • DRACULA   — the original dark v1.4 theme (default).
 *   • JUNGLE    — light/dark green palette.
 *   • SNOW      — light blue + white palette (white-snow).
 *
 * Each theme is a single CSS file under
 * {@code com/mst/matt/matthew_tube_downloader/themes/}.
 */
public final class ThemeManager {

    public enum Theme {
        DRACULA ("Dracula",     "themes/styles-dracula.css"),
        JUNGLE  ("Jungle",      "themes/styles-jungle.css"),
        SNOW    ("White Snow",  "themes/styles-snow.css");

        public final String displayName;
        public final String resourcePath;

        Theme(String displayName, String resourcePath) {
            this.displayName = displayName;
            this.resourcePath = resourcePath;
        }

        @Override public String toString() { return displayName; }

        public static Theme fromName(String name) {
            if (name == null) return DRACULA;
            for (Theme t : values()) {
                if (t.name().equalsIgnoreCase(name) || t.displayName.equalsIgnoreCase(name)) return t;
            }
            return DRACULA;
        }
    }

    private static WeakReference<Scene> sceneRef = new WeakReference<>(null);

    private ThemeManager() {}

    /** Called once from {@link HelloApplication#start} with the root scene. */
    public static void register(Scene scene) {
        sceneRef = new WeakReference<>(scene);
    }

    /** Apply a theme to the registered scene. Safe to call from any thread. */
    public static void apply(Theme theme) {
        if (theme == null) theme = Theme.DRACULA;
        Theme finalTheme = theme;
        Runnable r = () -> {
            Scene scene = sceneRef.get();
            if (scene == null) return;
            try {
                scene.getStylesheets().clear();
                String css = HelloApplication.class.getResource(finalTheme.resourcePath).toExternalForm();
                scene.getStylesheets().add(css);
            } catch (Exception e) {
                System.err.println("[theme] failed to apply " + finalTheme + ": " + e.getMessage());
            }
        };
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    /** Apply the theme currently saved in {@link AppSettings}. */
    public static void applyFromSettings() {
        apply(Theme.fromName(SettingsManager.load().themeName));
    }
}
