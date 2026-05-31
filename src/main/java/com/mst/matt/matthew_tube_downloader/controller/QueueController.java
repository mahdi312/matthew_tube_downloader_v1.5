package com.mst.matt.matthew_tube_downloader.controller;

import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.service.scheduler.DownloadScheduler;
import com.mst.matt.matthew_tube_downloader.service.scheduler.QueueItem;
import com.mst.matt.matthew_tube_downloader.service.settings.AppSettings;
import com.mst.matt.matthew_tube_downloader.service.settings.SettingsManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.function.Supplier;

/**
 * Controller for the "Queue" tab — Features 4 & 5 (and v1.4 extras).
 *
 * v1.4 additions:
 *   - Max-concurrent spinner (1–8) bound to {@link DownloadScheduler#setMaxConcurrentDownloads(int)}.
 *   - Start-all / Stop-all master buttons (with autostart switch).
 *   - "Restart from scratch" — deletes .part files and re-runs.
 */
public class QueueController {

    @FXML private TextField newUrlField;
    @FXML private RadioButton modeImmediate;
    @FXML private RadioButton modeManual;
    @FXML private RadioButton modeScheduled;
    @FXML private ToggleGroup timingGroup;
    @FXML private DatePicker schedDate;
    @FXML private TextField  schedTime;
    @FXML private Button addBtn;
    @FXML private Button addCurrentBtn;
    @FXML private Label  addStatusLabel;

    // v1.4 fields
    @FXML private Spinner<Integer> concurrencySpinner;
    @FXML private Label  autostartLabel;
    @FXML private Button startAllBtn;
    @FXML private Button stopAllBtn;
    @FXML private Button restartAllScratchBtn;
    @FXML private Button restartScratchBtn;
    @FXML private Label  concurrencyHintLabel;

    @FXML private Button runAllBtn;
    @FXML private Button pauseSelectedBtn;
    @FXML private Button resumeSelectedBtn;
    @FXML private Button removeSelectedBtn;
    @FXML private Button clearDoneBtn;

    @FXML private TableView<QueueItem> queueTable;
    @FXML private TableColumn<QueueItem, String> colLabel;
    @FXML private TableColumn<QueueItem, String> colStrategy;
    @FXML private TableColumn<QueueItem, String> colScheduled;
    @FXML private TableColumn<QueueItem, String> colStatus;
    @FXML private TableColumn<QueueItem, String> colProgress;
    @FXML private TableColumn<QueueItem, String> colMessage;
    @FXML private Label selectionInfoLabel;

    private DownloadScheduler scheduler;
    private Supplier<DownloadConfig> currentConfigSupplier = () -> null;

    @FXML
    public void initialize() {
        queueTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        colLabel.setCellValueFactory    (cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue().label));
        colStrategy.setCellValueFactory (cd -> new javafx.beans.property.SimpleStringProperty(
                cd.getValue().config.getStrategy() != null ? cd.getValue().config.getStrategy().getDisplayName() : ""));
        colScheduled.setCellValueFactory(cd -> cd.getValue().scheduledLabelProperty());
        colStatus.setCellValueFactory   (cd -> cd.getValue().statusLabelProperty());
        colProgress.setCellValueFactory (cd -> cd.getValue().progressLabelProperty());
        colMessage.setCellValueFactory  (cd -> cd.getValue().messageLabelProperty());

        queueTable.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<QueueItem>) c -> refreshSelectionInfo());

        schedDate.setValue(LocalDate.now());
        // Pre-fill the schedule time field: use default start time from settings if configured,
        // otherwise fall back to "now + 15 min".
        AppSettings s = SettingsManager.load();
        String defaultSchedTime = resolveDefaultSchedTimeText(s);
        schedTime.setText(defaultSchedTime);

        // v1.4: concurrency spinner — clamp 1..8, default from settings
        SpinnerValueFactory.IntegerSpinnerValueFactory svf =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 8,
                        Math.max(1, Math.min(8, s.maxConcurrentDownloads)));
        concurrencySpinner.setValueFactory(svf);
        concurrencySpinner.valueProperty().addListener((obs, oldV, newV) -> {
            if (scheduler != null && newV != null) scheduler.setMaxConcurrentDownloads(newV);
        });
    }

    /* ───────────────────────── wiring from MainController ───────────────────────── */

    public void setScheduler(DownloadScheduler scheduler) {
        this.scheduler = scheduler;
        queueTable.setItems(scheduler.items());
        scheduler.items().addListener((javafx.collections.ListChangeListener<QueueItem>) c -> refreshSelectionInfo());
        refreshSelectionInfo();
        refreshAutostartLabel();
        // Sync the spinner to whatever the scheduler is using right now
        if (concurrencySpinner.getValueFactory() != null) {
            concurrencySpinner.getValueFactory().setValue(scheduler.getMaxConcurrentDownloads());
        }
    }

    public void setCurrentConfigSupplier(Supplier<DownloadConfig> supplier) {
        this.currentConfigSupplier = (supplier == null) ? () -> null : supplier;
    }

    /* ───────────────────────── add actions ───────────────────────── */

    @FXML
    public void onAddToQueue() {
        String url = newUrlField.getText() == null ? "" : newUrlField.getText().trim();
        if (url.isEmpty()) {
            addStatusLabel.setText("⚠ Please paste a URL.");
            return;
        }
        if (scheduler == null) {
            addStatusLabel.setText("Scheduler not ready.");
            return;
        }

        AppSettings s = SettingsManager.load();
        DownloadConfig cfg = buildDefaultConfig(url, s);
        QueueItem item = new QueueItem();
        item.label = url;
        item.config = cfg;
        item.setScheduledAt(resolveScheduledAt());
        item.ensureTransients();
        scheduler.add(item);
        addStatusLabel.setText("✓ Added.");
        newUrlField.clear();
    }

    @FXML
    public void onAddFromCurrent() {
        String url = newUrlField.getText() == null ? "" : newUrlField.getText().trim();
        DownloadConfig live = currentConfigSupplier.get();
        if (live == null) {
            addStatusLabel.setText("⚠ Open the Download tab and fill the URL first, then click 'Add with current settings'.");
            return;
        }
        if (!url.isEmpty()) live.setUrl(url);
        if (live.getUrl() == null || live.getUrl().isBlank()) {
            addStatusLabel.setText("⚠ URL is empty. Either fill it on the Download tab or paste it here.");
            return;
        }
        if (scheduler == null) { addStatusLabel.setText("Scheduler not ready."); return; }

        QueueItem item = new QueueItem();
        item.label = live.getUrl();
        item.config = live;
        item.setScheduledAt(resolveScheduledAt());
        item.ensureTransients();
        scheduler.add(item);
        addStatusLabel.setText("✓ Added with current Download tab settings.");
        newUrlField.clear();
    }

    private Long resolveScheduledAt() {
        AppSettings s = SettingsManager.load();
        if (modeManual.isSelected()) return null;

        if (modeImmediate.isSelected()) {
            // If the user has a schedule window configured, honour the default start time
            // instead of starting immediately.
            if (s.queueUseScheduleWindow
                    && s.queueDefaultStartTime != null
                    && !s.queueDefaultStartTime.isBlank()) {
                Long scheduled = buildScheduledAt(s.queueDefaultStartTime, null);
                if (scheduled != null) return scheduled;
            }
            return System.currentTimeMillis();
        }

        // modeScheduled: parse the date + time fields in the UI.
        // Also fall back to the settings default start-time if the field is blank.
        String timeText = schedTime.getText() == null ? "" : schedTime.getText().trim();
        if (timeText.isBlank() && s.queueDefaultStartTime != null && !s.queueDefaultStartTime.isBlank()) {
            timeText = s.queueDefaultStartTime;
        }
        try {
            LocalDate d = schedDate.getValue() == null ? LocalDate.now() : schedDate.getValue();
            LocalTime t = parseTimeField(timeText);
            if (t == null) {
                addStatusLabel.setText("⚠ Invalid time format (use HH:mm). Using immediate.");
                return System.currentTimeMillis();
            }
            LocalDateTime when = LocalDateTime.of(d, t);
            long epoch = when.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            // If the chosen datetime is already in the past, advance to next day.
            if (epoch < System.currentTimeMillis()) {
                when = when.plusDays(1);
                epoch = when.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                addStatusLabel.setText("ℹ Scheduled time was in the past — moved to tomorrow.");
            }
            return epoch;
        } catch (Exception e) {
            addStatusLabel.setText("⚠ Invalid time format. Using immediate.");
            return System.currentTimeMillis();
        }
    }

    /**
     * Build a scheduledAt epoch for the given HH:mm string.
     * Returns today at that time if still in the future, otherwise tomorrow.
     * Returns null if parsing fails.
     */
    private Long buildScheduledAt(String timeStr, LocalDate baseDate) {
        try {
            LocalTime t = parseTimeField(timeStr);
            if (t == null) return null;
            LocalDate d = baseDate != null ? baseDate : LocalDate.now();
            LocalDateTime when = LocalDateTime.of(d, t);
            long epoch = when.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (epoch < System.currentTimeMillis()) {
                when = when.plusDays(1);
                epoch = when.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
            return epoch;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse a time string like "2:00", "02:00", "14:30" into a LocalTime.
     * Returns null on failure.
     */
    private LocalTime parseTimeField(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            // Accept both "H:mm" and "HH:mm"
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("H:mm")
                    .withResolverStyle(ResolverStyle.LENIENT);
            return LocalTime.parse(text.trim(), fmt);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compute the text to pre-fill into the schedTime field in initialize().
     * Uses queueDefaultStartTime if the window feature is on and the field is filled,
     * otherwise uses now+15 min.
     */
    private String resolveDefaultSchedTimeText(AppSettings s) {
        if (s.queueUseScheduleWindow
                && s.queueDefaultStartTime != null
                && !s.queueDefaultStartTime.isBlank()) {
            return s.queueDefaultStartTime;
        }
        return LocalTime.now().plusMinutes(15).withSecond(0).withNano(0)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private DownloadConfig buildDefaultConfig(String url, AppSettings s) {
        DownloadConfig c = new DownloadConfig();
        c.setUrl(url);
        c.setStrategy(s.defaultStrategy);
        c.setOutputDir(s.defaultOutputDir);
        c.setDownloadType(s.defaultDownloadType);
        if (s.defaultDownloadType != null) {
            c.setWantVideo(s.defaultDownloadType == DownloadConfig.DownloadType.VIDEO);
            c.setWantAudio(s.defaultDownloadType == DownloadConfig.DownloadType.AUDIO);
            c.setWantSubtitles(s.defaultDownloadType == DownloadConfig.DownloadType.SUBTITLES);
        }
        c.setVideoQuality(s.defaultQuality);
        c.setSubtitleLanguages(s.defaultSubtitleLangs);
        c.setSubFormat(s.defaultSubFormat);
        c.setSubType(s.defaultSubType);
        c.setEmbedSubtitles(s.defaultEmbedSubs);
        c.setUseProxy(s.defaultUseProxy);
        c.setProxyHost(s.defaultProxyHost);
        c.setProxyPort(s.defaultProxyPort);
        String cookies = SettingsManager.resolveCookiesPath(null);
        if (cookies != null) c.setCookiesPath(cookies);
        c.setInvidiousInstance(s.invidiousDefaultInstance);
        c.setInvidiousAutoRotate(s.invidiousDefaultAutoRotate);
        c.setGithubRepo(s.githubDefaultRepo);
        c.setGithubWorkflow(s.githubDefaultWorkflow);
        c.setGithubBranch(s.githubDefaultBranch);
        return c;
    }

    /* ───────────────────────── v1.4: master start/stop + restart-from-scratch ───────────────────────── */

    @FXML
    public void onStartAll() {
        if (scheduler == null) return;
        scheduler.startAll();
        refreshAutostartLabel();
    }

    @FXML
    public void onStopAll() {
        if (scheduler == null) return;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "This will pause/cancel every running download and stop the scheduler from launching new ones.\n\n"
                        + "yt-dlp items will be paused (resumable via .part files).\n"
                        + "Other strategies will be hard-cancelled.\n\n"
                        + "Click OK to stop all downloads.",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText("Stop all downloads?");
        if (a.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        scheduler.stopAll();
        refreshAutostartLabel();
    }

    @FXML
    public void onRestartAllScratch() {
        if (scheduler == null) return;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "This will delete any partial .part / .ytdl files for every queue item, "
                        + "then restart each one from byte 0.\n\n"
                        + "Already-completed items are skipped. Continue?",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText("Restart everything from scratch?");
        if (a.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        scheduler.restartAllFromScratch();
        refreshAutostartLabel();
    }

    @FXML
    public void onRestartSelectedScratch() {
        if (scheduler == null) return;
        List<QueueItem> sel = List.copyOf(queueTable.getSelectionModel().getSelectedItems());
        if (sel.isEmpty()) return;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete .part files and restart " + sel.size() + " selected item(s) from byte 0?",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText("Restart from scratch?");
        if (a.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        for (QueueItem it : sel) scheduler.restartFromScratch(it);
    }

    /* ───────────────────────── per-selection bulk actions ───────────────────────── */

    @FXML public void onRunAll() {
        // "Run selected" — keep the FXML method name for compatibility with the
        // existing onAction handler binding.
        if (scheduler == null) return;
        List<QueueItem> sel = List.copyOf(queueTable.getSelectionModel().getSelectedItems());
        if (sel.isEmpty()) { scheduler.runAllNow(); return; }
        for (QueueItem it : sel) scheduler.runNow(it);
    }

    @FXML public void onPauseSelected() {
        if (scheduler == null) return;
        List<QueueItem> sel = List.copyOf(queueTable.getSelectionModel().getSelectedItems());
        boolean anyNonResumable = false;
        for (QueueItem it : sel) {
            if (!DownloadScheduler.strategySupportsPause(it.config.getStrategy())) anyNonResumable = true;
        }
        if (anyNonResumable) {
            Alert a = new Alert(Alert.AlertType.WARNING);
            a.setTitle("Pause not supported");
            a.setHeaderText("Some items can't be truly paused");
            a.setContentText("Pause is only supported for yt-dlp and yt-dlp-proxy strategies.\n"
                    + "Other strategies will be hard-cancelled (you'll need to start them over).\n\n"
                    + "Continue anyway?");
            a.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            if (a.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        }
        for (QueueItem it : sel) scheduler.pause(it);
    }

    @FXML public void onResumeSelected() {
        if (scheduler == null) return;
        for (QueueItem it : List.copyOf(queueTable.getSelectionModel().getSelectedItems()))
            scheduler.resume(it);
    }

    @FXML public void onRemoveSelected() {
        if (scheduler == null) return;
        List<QueueItem> sel = List.copyOf(queueTable.getSelectionModel().getSelectedItems());
        if (sel.isEmpty()) return;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Remove " + sel.size() + " item(s) from the queue?", ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText("Confirm removal");
        if (a.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        for (QueueItem it : sel) scheduler.remove(it);
    }

    @FXML public void onClearCompleted() {
        if (scheduler != null) scheduler.clearCompleted();
    }

    private void refreshSelectionInfo() {
        int sel = queueTable.getSelectionModel().getSelectedItems().size();
        int total = scheduler == null ? 0 : scheduler.items().size();
        Platform.runLater(() -> selectionInfoLabel.setText(sel + " selected of " + total));
    }

    private void refreshAutostartLabel() {
        if (scheduler == null) return;
        boolean on = scheduler.isAutostartEnabled();
        Platform.runLater(() -> {
            autostartLabel.setText("Autostart: " + (on ? "ON" : "OFF (Stop all active)"));
            autostartLabel.setStyle(on
                    ? "-fx-text-fill: #4caf50;"
                    : "-fx-text-fill: #ff9800; -fx-font-weight: bold;");
        });
    }
}
