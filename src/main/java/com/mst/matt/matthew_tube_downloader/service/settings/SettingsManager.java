package com.mst.matt.matthew_tube_downloader.service.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Loads / saves {@link AppSettings} as JSON in the user's config directory.
 *
 * Location:
 *   • Windows: %APPDATA%/MatthewTubeDownloader/settings.json
 *   • macOS:   ~/Library/Application Support/MatthewTubeDownloader/settings.json
 *   • Linux:   ~/.matthew_tube_downloader/settings.json
 */
public final class SettingsManager {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    private static volatile AppSettings cached;

    private SettingsManager() {}

    public static Path configDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        Path dir;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            dir = Paths.get(appData != null ? appData : home, "MatthewTubeDownloader");
        } else if (os.contains("mac")) {
            dir = Paths.get(home, "Library", "Application Support", "MatthewTubeDownloader");
        } else {
            dir = Paths.get(home, ".matthew_tube_downloader");
        }
        return dir;
    }

    public static Path settingsFile() { return configDir().resolve("settings.json"); }
    public static Path queueFile()    { return configDir().resolve("queue.json"); }

    /** Load (or create defaults). Safe to call from any thread. */
    public static synchronized AppSettings load() {
        if (cached != null) return cached;
        Path f = settingsFile();
        try {
            if (Files.exists(f)) {
                String json = Files.readString(f, StandardCharsets.UTF_8);
                AppSettings s = GSON.fromJson(json, AppSettings.class);
                if (s != null) {
                    cached = s;
                    return s;
                }
            }
        } catch (Exception ignored) {
            // Fall through to defaults.
        }
        cached = new AppSettings();
        save(cached);
        return cached;
    }

    /** Persist immediately. */
    public static synchronized void save(AppSettings s) {
        cached = s;
        try {
            Files.createDirectories(configDir());
            Files.writeString(settingsFile(), GSON.toJson(s), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[SettingsManager] failed to save settings: " + e.getMessage());
        }
    }

    /** Replace the in-memory cache (e.g. after Settings tab applies changes). */
    public static synchronized void replace(AppSettings s) { save(s); }

    /**
     * Resolve the cookies file for yt-dlp.
     * Download-tab override wins; otherwise use {@link AppSettings#defaultCookiesPath}
     * when that file exists on disk.
     */
    public static String resolveCookiesPath(String downloadTabOverride) {
        String override = downloadTabOverride == null ? "" : downloadTabOverride.trim();
        if (!override.isEmpty() && Files.isRegularFile(Path.of(override))) {
            return override;
        }
        String fromSettings = load().defaultCookiesPath;
        if (fromSettings != null && !fromSettings.isBlank()) {
            String path = fromSettings.trim();
            if (Files.isRegularFile(Path.of(path))) return path;
        }
        return null;
    }

    /** True when a cookies path is configured (even if the file is missing). */
    public static boolean hasCookiesConfigured(String downloadTabOverride) {
        return !Objects.toString(downloadTabOverride, "").trim().isEmpty()
                || !Objects.toString(load().defaultCookiesPath, "").trim().isEmpty();
    }
}
