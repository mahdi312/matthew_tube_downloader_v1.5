package com.mst.matt.matthew_tube_downloader.controller;

import com.mst.matt.matthew_tube_downloader.HelloApplication;
import com.mst.matt.matthew_tube_downloader.MainController;
import com.mst.matt.matthew_tube_downloader.service.YtDlpService;
import com.mst.matt.matthew_tube_downloader.service.dependency.DependencyManager;
import com.mst.matt.matthew_tube_downloader.service.dependency.ToolStatus;
import com.mst.matt.matthew_tube_downloader.service.scheduler.DownloadScheduler;
import com.mst.matt.matthew_tube_downloader.service.settings.AppSettings;
import com.mst.matt.matthew_tube_downloader.service.settings.SettingsManager;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * Top-level controller for the new TabPane root.
 *
 * Responsibilities:
 *   - Lazy-loads each tab's FXML on demand (faster startup).
 *   - Wires QueueController to DownloadScheduler.
 *   - Wires QueueController.currentConfigSupplier to MainController.
 *   - Lets SettingsController push live updates back to MainController.
 *   - Runs the startup yt-dlp update check.
 */
public class RootController {

    @FXML private TabPane tabs;
    @FXML private Tab tabDownload;
    @FXML private Tab tabQueue;
    @FXML private Tab tabTools;
    @FXML private Tab tabSettings;
    @FXML private Label statusLeftLabel;
    @FXML private Label statusRightLabel;

    private MainController     mainController;
    private QueueController    queueController;
    private ToolsController    toolsController;
    private SettingsController settingsController;

    private final YtDlpService ytDlpService = new YtDlpService();
    private DownloadScheduler scheduler;

    public DownloadScheduler getScheduler() { return scheduler; }

    /** Called from HelloApplication.start() once the FXML graph is loaded. */
    public void bootstrap() {
        // v1.5: scheduler must exist BEFORE the Download tab loads so we can
        // inject it into MainController immediately (the Add-to-queue buttons
        // need it from the very first click).
        AppSettings s = SettingsManager.load();
        scheduler = new DownloadScheduler(ytDlpService);
        scheduler.setGlobalLog(line -> Platform.runLater(() -> statusLeftLabel.setText(line)));

        loadDownloadTab();
        // v1.5: hand the scheduler to MainController so the new ➕ buttons work.
        if (mainController != null) mainController.setScheduler(scheduler);

        tabQueue.setOnSelectionChanged(e -> { if (tabQueue.isSelected() && queueController == null) loadQueueTab(); });
        tabTools.setOnSelectionChanged(e -> { if (tabTools.isSelected() && toolsController == null) loadToolsTab(); });
        tabSettings.setOnSelectionChanged(e -> { if (tabSettings.isSelected() && settingsController == null) loadSettingsTab(); });

        if (s.enableSchedulerOnStart) scheduler.start();

        if (s.autoCheckYtDlpUpdates) checkYtDlpUpdateAsync();

        Platform.runLater(() -> statusLeftLabel.setText("Ready. Config: " + SettingsManager.configDir()));
    }

    private void loadDownloadTab() {
        try {
            FXMLLoader loader = new FXMLLoader(HelloApplication.class.getResource("main-view.fxml"));
            Parent node = loader.load();
            mainController = loader.getController();
            tabDownload.setContent(node);
        } catch (Exception e) {
            tabDownload.setContent(new Label("Failed to load Download tab: " + e.getMessage()));
        }
    }

    private void loadQueueTab() {
        try {
            FXMLLoader loader = new FXMLLoader(HelloApplication.class.getResource("queue-view.fxml"));
            Parent node = loader.load();
            queueController = loader.getController();
            queueController.setScheduler(scheduler);
            queueController.setCurrentConfigSupplier(() ->
                    mainController != null ? mainController.snapshotCurrentConfig() : null);
            tabQueue.setContent(node);
        } catch (Exception e) {
            tabQueue.setContent(new Label("Failed to load Queue tab: " + e.getMessage()));
        }
    }

    private void loadToolsTab() {
        try {
            FXMLLoader loader = new FXMLLoader(HelloApplication.class.getResource("tools-view.fxml"));
            Parent node = loader.load();
            toolsController = loader.getController();
            tabTools.setContent(node);
        } catch (Exception e) {
            tabTools.setContent(new Label("Failed to load Tools tab: " + e.getMessage()));
        }
    }

    private void loadSettingsTab() {
        try {
            FXMLLoader loader = new FXMLLoader(HelloApplication.class.getResource("settings-view.fxml"));
            Parent node = loader.load();
            settingsController = loader.getController();
            settingsController.setOnApplied(s -> {
                if (mainController != null) mainController.applySettings(s);
                // v1.4: push concurrency into the live scheduler
                if (scheduler != null) scheduler.setMaxConcurrentDownloads(s.maxConcurrentDownloads);
                Platform.runLater(() -> statusLeftLabel.setText("Settings applied"));
            });
            tabSettings.setContent(node);
        } catch (Exception e) {
            tabSettings.setContent(new Label("Failed to load Settings tab: " + e.getMessage()));
        }
    }

    private void checkYtDlpUpdateAsync() {
        Task<ToolStatus.State> t = new Task<>() {
            @Override protected ToolStatus.State call() { return new DependencyManager().quickCheckYtDlp(); }
        };
        t.setOnSucceeded(e -> {
            ToolStatus.State st = t.getValue();
            String msg = switch (st) {
                case OUTDATED -> "yt-dlp update available - open the Tools tab to update.";
                case MISSING  -> "yt-dlp not installed - open the Tools tab to install.";
                case OK       -> "yt-dlp is up to date";
                case UNKNOWN  -> "Could not check yt-dlp version (offline?).";
            };
            statusRightLabel.setText(msg);
            if (st == ToolStatus.State.OUTDATED || st == ToolStatus.State.MISSING) {
                statusRightLabel.setStyle("-fx-text-fill: #ff9800;");
            } else {
                statusRightLabel.setStyle("");
            }
        });
        new Thread(t, "yt-dlp-check").start();
    }
}
