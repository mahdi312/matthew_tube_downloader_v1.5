package com.mst.matt.matthew_tube_downloader.service.scheduler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mst.matt.matthew_tube_downloader.service.settings.SettingsManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Load/save the queue list as JSON. */
public final class QueuePersistence {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private QueuePersistence() {}

    public static synchronized List<QueueItem> load() {
        Path f = SettingsManager.queueFile();
        try {
            if (!Files.exists(f)) return new ArrayList<>();
            String json = Files.readString(f, StandardCharsets.UTF_8);
            List<QueueItem> list = GSON.fromJson(json, new TypeToken<List<QueueItem>>(){}.getType());
            if (list == null) return new ArrayList<>();
            for (QueueItem it : list) {
                it.ensureTransients();
                // Items left in RUNNING/PAUSED from a previous crash → reset to QUEUED for safety
                if (it.status == QueueItem.Status.RUNNING) it.setStatus(QueueItem.Status.PAUSED);
            }
            return list;
        } catch (Exception e) {
            System.err.println("[queue] load failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static synchronized void save(List<QueueItem> items) {
        try {
            Files.createDirectories(SettingsManager.configDir());
            Files.writeString(SettingsManager.queueFile(),
                    GSON.toJson(items), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[queue] save failed: " + e.getMessage());
        }
    }
}
