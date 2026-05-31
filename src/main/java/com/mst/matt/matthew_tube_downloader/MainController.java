package com.mst.matt.matthew_tube_downloader;

import com.mst.matt.matthew_tube_downloader.HelloApplication;
import com.mst.matt.matthew_tube_downloader.controller.QualityPickerController;
import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.model.VideoInfo;
import com.mst.matt.matthew_tube_downloader.model.VideoQualityOption;
import com.mst.matt.matthew_tube_downloader.service.DownloadTask;
import com.mst.matt.matthew_tube_downloader.service.YtDlpService;
import com.mst.matt.matthew_tube_downloader.service.extractor.FormatInfo;
import com.mst.matt.matthew_tube_downloader.service.extractor.WebpageExtractor;
import com.mst.matt.matthew_tube_downloader.service.scheduler.DownloadScheduler;
import com.mst.matt.matthew_tube_downloader.service.scheduler.QueueItem;
import com.mst.matt.matthew_tube_downloader.service.settings.AppSettings;
import com.mst.matt.matthew_tube_downloader.service.settings.SettingsManager;
import com.mst.matt.matthew_tube_downloader.service.strategy.StrategyType;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main controller for the Matthew Tube Downloader UI.
 *
 * v1.2 — adds the "Download Strategy" section (5 strategies + info option) wired
 * via {@link com.mst.matt.matthew_tube_downloader.service.strategy.StrategyType}.
 * All previous behavior is preserved when the default strategy (yt-dlp Direct) is selected.
 */
public class MainController {

    // ── URL Section ──
    @FXML private TextField urlField;
    @FXML private Button analyzeBtn;
    @FXML private Label urlStatusLabel;

    // ── Info Section ──
    @FXML private VBox infoBox;
    @FXML private Label contentTypeLabel;
    @FXML private Label contentTitleLabel;

    // ── Playlist Section ──
    @FXML private VBox playlistBox;
    @FXML private TableView<VideoInfo> playlistTable;
    @FXML private TableColumn<VideoInfo, Boolean> colSelect;
    @FXML private TableColumn<VideoInfo, Number> colIndex;
    @FXML private TableColumn<VideoInfo, String> colTitle;
    @FXML private TableColumn<VideoInfo, String> colDuration;
    @FXML private TableColumn<VideoInfo, String> colStatus;
    @FXML private TextField rangeField;
    @FXML private Label selectionCountLabel;

    // ── Download Type (multi-select) ──
    @FXML private VBox downloadTypeBox;
    @FXML private CheckBox checkVideo;
    @FXML private CheckBox checkAudio;
    @FXML private CheckBox checkSubs;

    // ── Video Quality (dynamic) ──
    @FXML private VBox videoQualityBox;
    @FXML private ComboBox<VideoQualityOption> qualityCombo;
    @FXML private Label qualityHintLabel;

    // ── Subtitle Options ──
    @FXML private VBox subtitleOptionsBox;
    @FXML private TextField subLangField;
    @FXML private RadioButton subSrt;
    @FXML private RadioButton subVtt;
    @FXML private ToggleGroup subFormatGroup;
    @FXML private RadioButton subAll;
    @FXML private RadioButton subManual;
    @FXML private RadioButton subAuto;
    @FXML private ToggleGroup subTypeGroup;
    @FXML private CheckBox embedSubsCheck;

    // ── Output Settings ──
    @FXML private VBox outputBox;
    @FXML private TextField outputDirField;
    @FXML private TextField cookiesField;

    // ── Proxy Settings ──
    @FXML private VBox proxyBox;
    @FXML private CheckBox useProxyCheck;
    @FXML private TextField proxyHostField;
    @FXML private TextField proxyPortField;

    // ── v1.5 Site type badge ──
    @FXML private Label siteTypeLabel;

    // ── v1.5 Add-to-queue buttons ──
    @FXML private Button addUrlToQueueBtn;
    @FXML private Button addSelectedToQueueBtn;

    // ── v1.2 Strategy Section ──
    @FXML private VBox strategyBox;
    @FXML private ComboBox<StrategyType> strategyCombo;
    @FXML private Label strategyDescLabel;
    @FXML private VBox invidiousBox;
    @FXML private TextField invidiousInstanceField;
    @FXML private CheckBox invidiousAutoRotateCheck;
    @FXML private VBox githubBox;
    @FXML private TextField githubRepoField;
    @FXML private TextField githubWorkflowField;
    @FXML private TextField githubBranchField;
    @FXML private PasswordField githubTokenField;

    // ── Download Controls ──
    @FXML private HBox downloadBtnBox;
    @FXML private Button downloadBtn;
    @FXML private Button cancelBtn;
    @FXML private Button openFolderBtn;

    // ── Progress ──
    @FXML private VBox progressBox;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;

    // ── Log ──
    @FXML private VBox logBox;
    @FXML private TextArea logArea;
    @FXML private Button saveLogBtn;

    // ── Status Bar ──
    @FXML private Label ytDlpStatusLabel;

    // ── Internal State ──
    private final YtDlpService ytDlpService = new YtDlpService();
    private final WebpageExtractor extractor = new WebpageExtractor();
    private final ObservableList<VideoInfo> playlistEntries = FXCollections.observableArrayList();
    private boolean isPlaylist = false;
    private DownloadTask currentDownloadTask;
    private boolean ytDlpAvailable = false;

    /** v1.5: scheduler reference injected by RootController so 'Add to queue'
     *  works from the Download tab without opening the Queue tab. */
    private DownloadScheduler scheduler;

    /** v1.5: injected by RootController. */
    public void setScheduler(DownloadScheduler scheduler) { this.scheduler = scheduler; }

    /** Format id picked by the Quality Picker for non-YouTube URLs (null = none). */
    private String pickedFormatId;
    /** Pretty label for the picked format (shown in the log). */
    private String pickedFormatLabel;

    @FXML
    public void initialize() {
        // Default output directory
        outputDirField.setText(System.getProperty("user.home") + File.separator + "Downloads");

        // v1.5: default v2rayN SOCKS5 inbound is 10808 (was 12334).
        proxyHostField.setText("127.0.0.1");
        proxyPortField.setText("10808");

        // Strategy ComboBox setup
        setupStrategyCombo();

        // Setup table columns
        setupPlaylistTable();

        // Listen for download type changes
        checkVideo.selectedProperty().addListener((obs, o, n) -> updateOptionsVisibility());
        checkAudio.selectedProperty().addListener((obs, o, n) -> updateOptionsVisibility());
        checkSubs.selectedProperty().addListener((obs, o, n) -> updateOptionsVisibility());

        qualityCombo.setItems(FXCollections.observableArrayList(VideoQualityOption.bestFallback()));
        qualityCombo.getSelectionModel().selectFirst();

        // v1.5: live site-type detection while the user types.
        urlField.textProperty().addListener((obs, oldV, newV) -> updateSiteTypeBadge(newV));
        updateSiteTypeBadge(urlField.getText());

        // Check yt-dlp availability in background
        checkYtDlp();

        // v1.3 - apply persisted defaults from Settings before the user touches anything.
        try { applySettings(SettingsManager.load()); } catch (Exception ignored) {}

        log("Matthew Tube Downloader initialized.");
        log("Ready to download. Paste a YouTube URL and click Analyze.");
        log("Tip: v2rayN users — enable 'Use Proxy' and confirm port 10808 (the default v2rayN SOCKS5 inbound).");
        log("Non-YouTube URLs will open a Quality Picker after analysis.");
    }

    /** v1.5: live site classification (YouTube vs generic). */
    private void updateSiteTypeBadge(String url) {
        if (siteTypeLabel == null) return;
        if (url == null || url.isBlank()) {
            siteTypeLabel.setText("");
            return;
        }
        if (extractor.looksLikeYouTube(url)) {
            siteTypeLabel.setText("📍 YouTube");
            siteTypeLabel.setStyle("-fx-text-fill: #e53935; -fx-font-weight: bold;");
        } else {
            siteTypeLabel.setText("🌐 Generic site");
            siteTypeLabel.setStyle("-fx-text-fill: #2196f3; -fx-font-weight: bold;");
            // Non-YouTube URLs only work with the yt-dlp Direct strategy.
            // Auto-switch if user picked an Invidious / PureJava strategy.
            StrategyType cur = strategyCombo.getValue();
            if (cur == StrategyType.INVIDIOUS || cur == StrategyType.PURE_JAVA) {
                strategyCombo.setValue(StrategyType.YT_DLP_DIRECT);
                log("Strategy auto-switched to yt-dlp Direct (Invidious / PureJava are YouTube-only).");
            }
        }
    }

    /** Populate the strategy combo and react to selection changes. */
    private void setupStrategyCombo() {
        strategyCombo.getItems().setAll(StrategyType.values());
        strategyCombo.setValue(StrategyType.YT_DLP_DIRECT);
        strategyDescLabel.setText(StrategyType.YT_DLP_DIRECT.getDescription());

        strategyCombo.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) return;
            strategyDescLabel.setText(newV.getDescription());
            showSection(invidiousBox, newV == StrategyType.INVIDIOUS);
            showSection(githubBox,    newV == StrategyType.GITHUB_ACTIONS);
            if (newV == StrategyType.SNI_INFO) {
                Platform.runLater(this::showSniInfoDialog);
            }
        });
    }

    /** Setup the playlist table columns and bindings. */
    private void setupPlaylistTable() {
        playlistTable.setItems(playlistEntries);
        playlistTable.setEditable(true);
        colSelect.setCellValueFactory(cellData -> cellData.getValue().selectedProperty());
        colSelect.setCellFactory(col -> new CheckBoxTableCell<>());
        colSelect.setEditable(true);
        colIndex.setCellValueFactory(cellData -> cellData.getValue().indexProperty());
        colTitle.setCellValueFactory(cellData -> cellData.getValue().titleProperty());
        colDuration.setCellValueFactory(cellData -> cellData.getValue().durationProperty());
        colStatus.setCellValueFactory(cellData -> cellData.getValue().statusProperty());
    }

    /** Check yt-dlp; auto-install if missing. */
    private void checkYtDlp() {
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                boolean available = ytDlpService.isYtDlpAvailable();
                if (!available) {
                    Platform.runLater(() ->
                            ytDlpStatusLabel.setText("yt-dlp not found, trying to install..."));
                    ytDlpService.installYtDlp(msg -> Platform.runLater(() -> log(msg)));
                    available = ytDlpService.isYtDlpAvailable();
                }
                return available;
            }
        };
        task.setOnSucceeded(e -> {
            ytDlpAvailable = task.getValue();
            if (ytDlpAvailable) {
                String version = ytDlpService.getYtDlpVersion();
                ytDlpStatusLabel.setText("yt-dlp " + version + " ✓");
                ytDlpStatusLabel.setStyle("-fx-text-fill: #4caf50;");
                log("yt-dlp found: version " + version);
            } else {
                ytDlpStatusLabel.setText("yt-dlp NOT FOUND ✗  (only Invidious / pure-Java / GitHub strategies will work)");
                ytDlpStatusLabel.setStyle("-fx-text-fill: #ff9800;");
                log("WARNING: yt-dlp not installed.");
                log("  • yt-dlp & yt-dlp-proxy strategies won't work.");
                log("  • Invidious / Pure-Java / GitHub Actions strategies will still work.");
                log("  • To install: pip install yt-dlp");
            }
            // Analyze button stays enabled if any strategy is usable; we check at runtime.
            analyzeBtn.setDisable(false);
        });
        new Thread(task).start();
    }

    /* ═══════════════════════════════════════════════════════
        FXML Action Handlers
       ═══════════════════════════════════════════════════════ */

    @FXML
    private void onStrategyInfo() {
        StrategyType t = strategyCombo.getValue();
        if (t == null) return;

        Alert info = new Alert(Alert.AlertType.INFORMATION);
        info.setTitle(t.getDisplayName());
        info.setHeaderText(t.getDisplayName());

        String body = switch (t) {
            case YT_DLP_DIRECT -> "Runs yt-dlp directly on your machine.\n\n" +
                    "Use this when your network reaches youtube.com normally\n" +
                    "(or via the SOCKS5 proxy field below).";

            case INVIDIOUS -> "Hits a public Invidious / Piped instance and downloads the\n" +
                    "direct googlevideo.com URL with Java's HttpClient.\n\n" +
                    "Why it works in Iran: ISPs filter SNI for youtube.com but typically\n" +
                    "not googlevideo.com, so the resolved CDN URL is reachable.\n\n" +
                    "Configure: optional explicit instance + auto-rotate fallback list.\n" +
                    "Repos: iv-org/invidious, TeamPiped/Piped";

            case PURE_JAVA -> "Embedded pure-Java YouTube parser\n" +
                    "(sealedtx/java-youtube-downloader on JitPack).\n\n" +
                    "No yt-dlp binary needed. Honors the SOCKS5 proxy field.\n" +
                    "Best when yt-dlp is hard to install or YouTube format changes\n" +
                    "outpace your local yt-dlp version.";

            case YT_DLP_PROXY -> "Wraps yt-dlp through `yt-dlp-proxy`, which auto-fetches\n" +
                    "free public proxies and picks the fastest one.\n\n" +
                    "Install:  pip install yt-dlp-proxy   then run:  yt-dlp-proxy update\n" +
                    "Repo:     https://github.com/Petrprogs/yt-dlp-proxy";

            case GITHUB_ACTIONS -> "Offloads the download to a GitHub Actions runner.\n\n" +
                    "Fork an aio-downloader repo (e.g. ProAlit/aio-downloader or\n" +
                    "alitavakoli01/YouTubeDownloader), then fill in:\n" +
                    "  • Repo (owner/name)\n  • Workflow file (default download.yml)\n" +
                    "  • Branch (default main)\n  • Personal Access Token\n\n" +
                    "Works during severe Iran filtering as long as github.com loads.";

            case SNI_INFO -> "System-level SNI/DPI bypass — Waujito/youtubeUnblock.\n\n" +
                    "Linux / OpenWRT utility that fragments TLS ClientHello packets\n" +
                    "to defeat SNI-based DPI. Not Java-integrable — install it\n" +
                    "separately on your router / box.\n\n" +
                    "Repo: https://github.com/Waujito/youtubeUnblock";
        };
        info.setContentText(body);
        info.showAndWait();
    }

    private void showSniInfoDialog() {
        Alert info = new Alert(Alert.AlertType.INFORMATION);
        info.setTitle("SNI/DPI Bypass — not a download method");
        info.setHeaderText("This is a system-level helper, not a download strategy");
        info.setContentText("Install Waujito/youtubeUnblock on your router/Linux box.\n\n" +
                "After install, switch back to one of the real download strategies\n" +
                "(yt-dlp Direct works well once SNI filtering is defeated).\n\n" +
                "Repo: https://github.com/Waujito/youtubeUnblock");
        info.showAndWait();
    }

    @FXML
    private void onAnalyze() {
        String url = YtDlpService.normalizeYoutubeUrl(urlField.getText().trim());
        if (url.isEmpty()) {
            urlStatusLabel.setText("⚠ Please enter a URL");
            urlStatusLabel.setStyle("-fx-text-fill: #ff9800;");
            return;
        }
        if (!url.equals(urlField.getText().trim())) {
            urlField.setText(url);
        }

        String proxyUrl = buildProxyUrl();
        final String cookiesPath = SettingsManager.resolveCookiesPath(
                cookiesField.getText() == null ? "" : cookiesField.getText().trim());
        final String invidiousInstance = invidiousInstanceField.getText() == null
                ? "" : invidiousInstanceField.getText().trim();

        resetAnalysis();
        analyzeBtn.setDisable(true);
        urlStatusLabel.setText("🔄 Analyzing URL...");
        urlStatusLabel.setStyle("-fx-text-fill: #2196f3;");
        showSection(logBox, true);
        log("Analyzing: " + url);
        if (proxyUrl != null) log("Using proxy: " + proxyUrl);
        if (cookiesPath != null) log("Using cookies: " + cookiesPath);

        // Analysis still uses yt-dlp when available, regardless of chosen download strategy
        // (because yt-dlp gives the richest metadata + playlist enumeration).
        // If yt-dlp is missing, we fall back to a simple single-video stub so the user
        // can still try Invidious / Pure-Java / GitHub strategies.
        final boolean canUseYtDlp = ytDlpAvailable;
        final boolean isYouTube = extractor.looksLikeYouTube(url);
        // v1.3 - reset the picked-format-id from any previous run.
        pickedFormatId = null;
        pickedFormatLabel = null;

        Task<Void> analyzeTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                // v1.3 - Feature 2+3: for non-YouTube URLs, run the generic extractor
                // and pop a Quality Picker dialog.
                if (!isYouTube) {
                    Platform.runLater(() -> log("Non-YouTube URL detected - using generic extractor."));
                    WebpageExtractor.ExtractResult res = extractor.extract(url, proxyUrl,
                            line -> Platform.runLater(() -> log(line)));
                    Platform.runLater(() -> {
                        isPlaylist = false;
                        contentTypeLabel.setText("GENERIC");
                        contentTitleLabel.setText(res.title != null ? res.title : url);
                        showSection(infoBox, true);
                        if (res.formats.isEmpty()) {
                            log("No streams discovered. You can still try a strategy that handles this site directly.");
                        } else {
                            showQualityPicker(res);
                        }
                    });
                    return null;
                }

                if (!canUseYtDlp) {
                    Platform.runLater(() -> {
                        isPlaylist = false;
                        contentTypeLabel.setText("VIDEO");
                        contentTitleLabel.setText("(yt-dlp unavailable - title unknown)");
                        showSection(infoBox, true);
                        log("yt-dlp unavailable - skipping metadata fetch. The chosen strategy will handle it.");
                    });
                    return null;
                }

                String playlistTitle = null;
                List<VideoInfo> entries = List.of();
                if (YtDlpService.urlLooksLikeExplicitPlaylist(url)) {
                    playlistTitle = ytDlpService.detectPlaylistTitle(url, proxyUrl, cookiesPath);
                    entries = ytDlpService.fetchPlaylistEntries(url, proxyUrl, cookiesPath);
                }

                if (YtDlpService.urlLooksLikeExplicitPlaylist(url)
                        && entries.size() > 1
                        && YtDlpService.isUsableTitle(playlistTitle)) {
                    final String title = playlistTitle;
                    final List<VideoInfo> playlist = entries;
                    Platform.runLater(() -> {
                        isPlaylist = true;
                        contentTypeLabel.setText("PLAYLIST");
                        contentTitleLabel.setText(title);
                        showSection(infoBox, true);
                        log("Detected playlist: " + title);
                        urlStatusLabel.setText("🔄 Loading playlist entries...");
                    });
                    Platform.runLater(() -> {
                        playlistEntries.setAll(playlist);
                        showSection(playlistBox, true);
                        updateSelectionCount();
                        log("Found " + playlist.size() + " videos in playlist.");
                    });
                } else {
                    String title = ytDlpService.getVideoTitle(url, proxyUrl, cookiesPath, invidiousInstance);
                    Platform.runLater(() -> {
                        isPlaylist = false;
                        contentTypeLabel.setText("VIDEO");
                        contentTitleLabel.setText(title);
                        showSection(infoBox, true);
                        log("Detected single video: " + title);
                        if (title != null && title.contains("unavailable")) {
                            log("Tip: set cookies.txt in Settings (or Download tab override), enable proxy, then Analyze again.");
                        }
                    });
                }
                return null;
            }
        };

        analyzeTask.setOnSucceeded(e -> {
            analyzeBtn.setDisable(false);
            urlStatusLabel.setText("✓ Analysis complete");
            urlStatusLabel.setStyle("-fx-text-fill: #4caf50;");
            showSection(downloadTypeBox, true);
            showSection(outputBox, true);
            showSection(proxyBox, true);
            showSection(downloadBtnBox, true);
            showSection(logBox, true);
            updateOptionsVisibility();
            if (isYouTube && canUseYtDlp) {
                refreshAvailableQualities(url, proxyUrl, cookiesPath);
            }
        });

        analyzeTask.setOnFailed(e -> {
            analyzeBtn.setDisable(false);
            Throwable ex = analyzeTask.getException();
            urlStatusLabel.setText("✗ Analysis failed");
            urlStatusLabel.setStyle("-fx-text-fill: #f44336;");
            log("ERROR: " + (ex != null ? ex.getMessage() : "Analysis failed"));
            // Even on analyze failure, allow user to proceed with non-yt-dlp strategies on a single video.
            showSection(downloadTypeBox, true);
            showSection(outputBox, true);
            showSection(downloadBtnBox, true);
            showSection(logBox, true);
            updateOptionsVisibility();
        });

        new Thread(analyzeTask).start();
    }

    @FXML
    private void onSelectAll() {
        Platform.runLater(() -> {
            for (VideoInfo v : playlistEntries) v.setSelected(true);
            updateSelectionCount();
        });
    }

    @FXML
    private void onDeselectAll() {
        Platform.runLater(() -> {
            for (VideoInfo v : playlistEntries) v.setSelected(false);
            updateSelectionCount();
        });
    }

    @FXML
    private void onApplyRange() {
        String rangeText = rangeField.getText().trim();
        if (rangeText.isEmpty()) return;

        Platform.runLater(() -> {
            for (VideoInfo v : playlistEntries) v.setSelected(false);
            List<Integer> indices = parseRangeExpression(rangeText);
            for (VideoInfo v : playlistEntries) {
                if (indices.contains(v.getIndex())) v.setSelected(true);
            }
            updateSelectionCount();
            log("Applied range selection: " + rangeText + " → " + indices.size() + " items");
        });
    }

    @FXML
    private void onBrowseOutput() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Output Directory");
        String current = outputDirField.getText();
        if (current != null && !current.isEmpty()) {
            File dir = new File(current);
            if (dir.exists()) chooser.setInitialDirectory(dir);
        }
        File selected = chooser.showDialog(outputDirField.getScene().getWindow());
        if (selected != null) outputDirField.setText(selected.getAbsolutePath());
    }

    @FXML
    private void onBrowseCookies() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Cookies File");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        File selected = chooser.showOpenDialog(cookiesField.getScene().getWindow());
        if (selected != null) cookiesField.setText(selected.getAbsolutePath());
    }

    @FXML
    private void onOpenOutputFolder() {
        String outputDir = outputDirField.getText().trim();
        if (outputDir.isEmpty())
            outputDir = System.getProperty("user.home") + File.separator + "Downloads";
        File dir = new File(outputDir);
        if (dir.exists() && dir.isDirectory()) {
            try {
                Desktop.getDesktop().open(dir);
            } catch (Exception e) {
                log("Could not open folder: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onStartDownload() {
        DownloadConfig config = buildConfig();
        if (config == null) return;

        // SNI_INFO is not a real download method.
        if (config.getStrategy() == StrategyType.SNI_INFO) {
            showSniInfoDialog();
            return;
        }

        downloadBtn.setDisable(true);
        openFolderBtn.setDisable(true);
        showSection(cancelBtn, true);
        showSection(progressBox, true);
        showSection(logBox, true);
        progressBar.setProgress(0);
        progressLabel.setText("Starting...");

        log("═".repeat(60));
        log("Starting download at " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        log("Strategy: " + config.getStrategy().getDisplayName());

        if (isPlaylist && !playlistEntries.isEmpty()) {
            currentDownloadTask = new DownloadTask(config, ytDlpService, playlistEntries);
        } else {
            currentDownloadTask = new DownloadTask(config, ytDlpService, null);
        }

        progressBar.progressProperty().bind(currentDownloadTask.progressProperty());

        currentDownloadTask.messageProperty().addListener((obs, oldMsg, newMsg) -> {
            if (newMsg != null && !newMsg.isEmpty()) {
                progressLabel.setText(truncate(newMsg, 100));
                log(newMsg);
            }
        });

        currentDownloadTask.setOnSucceeded(e -> {
            Integer exit = currentDownloadTask.getValue();
            String lastMsg = currentDownloadTask.getMessage();
            if (exit != null && exit == 0) {
                onDownloadFinished(
                        lastMsg != null && !lastMsg.isBlank() ? lastMsg : "Download completed successfully!",
                        true);
            } else if (exit != null && exit == -1) {
                onDownloadFinished("Download cancelled by user.", false);
            } else {
                onDownloadFinished(
                        lastMsg != null && !lastMsg.isBlank()
                                ? lastMsg
                                : "Download failed (exit code " + exit + "). See log for yt-dlp errors.",
                        false);
            }
        });
        currentDownloadTask.setOnFailed(e -> {
            Throwable ex = currentDownloadTask.getException();
            onDownloadFinished("Download failed: " + (ex != null ? ex.getMessage() : "unknown"), false);
        });
        currentDownloadTask.setOnCancelled(e ->
                onDownloadFinished("Download cancelled by user.", false));

        Thread downloadThread = new Thread(currentDownloadTask);
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    @FXML
    private void onCancelDownload() {
        if (currentDownloadTask != null && currentDownloadTask.isRunning()) {
            currentDownloadTask.cancel(true);
            log("Cancelling download...");
        }
    }

    @FXML
    private void onClearLog() { logArea.clear(); }

    @FXML
    private void onSaveLog() {
        String logContent = logArea.getText();
        if (logContent == null || logContent.isEmpty()) {
            log("No log content to save.");
            return;
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String defaultFileName = "matthew_tube_log_" + timestamp + ".txt";
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Log File");
        fileChooser.setInitialFileName(defaultFileName);
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        String defaultDir = System.getProperty("user.home") + File.separator + "Downloads";
        File defaultDirectory = new File(defaultDir);
        if (defaultDirectory.exists()) fileChooser.setInitialDirectory(defaultDirectory);
        Stage stage = (Stage) logArea.getScene().getWindow();
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try {
                java.nio.file.Files.writeString(file.toPath(), logContent);
                log("Log saved to: " + file.getAbsolutePath());
            } catch (Exception e) {
                log("Error saving log: " + e.getMessage());
            }
        }
    }

    /* ═══════════════════════════════════════════════════════
        Helper Methods
       ═══════════════════════════════════════════════════════ */

    private String buildProxyUrl() {
        if (useProxyCheck.isSelected()) {
            String host = proxyHostField.getText().trim();
            String port = proxyPortField.getText().trim();
            if (!host.isEmpty() && !port.isEmpty()) return "socks5://" + host + ":" + port;
        }
        return null;
    }

    private void onDownloadFinished(String message, boolean success) {
        progressBar.progressProperty().unbind();
        if (success) {
            progressBar.setProgress(1.0);
        } else {
            progressBar.setProgress(0);
        }
        progressLabel.setText(message);
        downloadBtn.setDisable(false);
        openFolderBtn.setDisable(false);
        showSection(cancelBtn, false);
        // Final message already logged by the task message listener — avoid duplicate line.
        if (!success) {
            log("Tip: update yt-dlp (`yt-dlp -U`), refresh cookies.txt, or try Invidious strategy if formats fail.");
        }
        log("═".repeat(60));
    }

    private DownloadConfig buildConfig() {
        DownloadConfig config = new DownloadConfig();
        config.setUrl(urlField.getText().trim());

        if (config.getUrl().isEmpty()) {
            showAlert("No URL", "Please enter a YouTube URL first.");
            return null;
        }

        // ── Strategy ──
        StrategyType strat = strategyCombo.getValue();
        if (strat == null) strat = StrategyType.YT_DLP_DIRECT;
        config.setStrategy(strat);

        // Strategy-specific config
        config.setInvidiousInstance(safe(invidiousInstanceField.getText()));
        config.setInvidiousAutoRotate(invidiousAutoRotateCheck.isSelected());
        config.setGithubRepo(safe(githubRepoField.getText()));
        config.setGithubWorkflow(safe(githubWorkflowField.getText()));
        config.setGithubBranch(safe(githubBranchField.getText()));
        config.setGithubToken(safe(githubTokenField.getText()));

        // ── Output ──
        String outDir = outputDirField.getText().trim();
        if (outDir.isEmpty()) outDir = System.getProperty("user.home") + File.separator + "Downloads";
        config.setOutputDir(outDir);

        // ── Cookies (Download tab override, else Settings default when file exists) ──
        String cookies = SettingsManager.resolveCookiesPath(cookiesField.getText().trim());
        if (cookies != null) config.setCookiesPath(cookies);

        // ── Proxy ──
        config.setUseProxy(useProxyCheck.isSelected());
        config.setProxyHost(proxyHostField.getText().trim());
        config.setProxyPort(proxyPortField.getText().trim());

        // ── Types (multi-select) ──
        if (!checkVideo.isSelected() && !checkAudio.isSelected() && !checkSubs.isSelected()) {
            showAlert("No Selection", "Select at least one download type (Video, Audio, or Subtitles).");
            return null;
        }
        config.setWantVideo(checkVideo.isSelected());
        config.setWantAudio(checkAudio.isSelected());
        config.setWantSubtitles(checkSubs.isSelected());
        // Primary type for legacy paths
        if (checkVideo.isSelected())      config.setDownloadType(DownloadConfig.DownloadType.VIDEO);
        else if (checkAudio.isSelected()) config.setDownloadType(DownloadConfig.DownloadType.AUDIO);
        else                              config.setDownloadType(DownloadConfig.DownloadType.SUBTITLES);

        // ── Quality (combo wins over stale Quality-Picker id) ──
        config.setPickedFormatId("");
        if (checkVideo.isSelected()) {
            VideoQualityOption q = qualityCombo.getSelectionModel().getSelectedItem();
            if (q != null && q.formatSpec() != null && !q.formatSpec().isBlank()) {
                config.setPickedFormatId(q.formatSpec());
            }
        } else if (pickedFormatId != null && !pickedFormatId.isBlank()) {
            config.setPickedFormatId(pickedFormatId);
        }

        // ── Subtitles ──
        config.setSubtitleLanguages(subLangField.getText().trim());
        config.setSubFormat(subSrt.isSelected() ? DownloadConfig.SubFormat.SRT : DownloadConfig.SubFormat.VTT);
        if      (subAll.isSelected())    config.setSubType(DownloadConfig.SubType.ALL);
        else if (subManual.isSelected()) config.setSubType(DownloadConfig.SubType.MANUAL);
        else                             config.setSubType(DownloadConfig.SubType.AUTO);
        config.setEmbedSubtitles(embedSubsCheck.isSelected());

        // ── Playlist ──
        config.setPlaylist(isPlaylist);
        if (isPlaylist) {
            List<Integer> selected = new ArrayList<>();
            boolean allSelected = true;
            for (VideoInfo v : playlistEntries) {
                if (v.isSelected()) selected.add(v.getIndex());
                else                allSelected = false;
            }
            if (selected.isEmpty()) {
                showAlert("No Selection", "Please select at least one video from the playlist.");
                return null;
            }
            config.setDownloadAll(allSelected);
            if (!allSelected) config.setSelectedIndices(selected);
        }

        return config;
    }

    private String safe(String s) { return s == null ? "" : s.trim(); }

    private List<Integer> parseRangeExpression(String expr) {
        List<Integer> result = new ArrayList<>();
        String[] parts = expr.split("[,\\s]+");
        Pattern rangePattern = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            Matcher m = rangePattern.matcher(part);
            if (m.matches()) {
                int start = Integer.parseInt(m.group(1));
                int end = Integer.parseInt(m.group(2));
                for (int i = Math.min(start, end); i <= Math.max(start, end); i++) {
                    if (!result.contains(i)) result.add(i);
                }
            } else {
                try {
                    int num = Integer.parseInt(part);
                    if (!result.contains(num)) result.add(num);
                } catch (NumberFormatException ignored) {}
            }
        }
        return result;
    }

    private void updateOptionsVisibility() {
        boolean wantsVideo = checkVideo.isSelected();
        boolean wantsSubs  = checkSubs.isSelected();
        showSection(videoQualityBox, wantsVideo);
        showSection(subtitleOptionsBox, wantsVideo || wantsSubs);
        embedSubsCheck.setVisible(wantsVideo);
        embedSubsCheck.setManaged(wantsVideo);
    }

    /** Load real available heights (720p–4K) from yt-dlp after Analyze. */
    private void refreshAvailableQualities(String url, String proxyUrl, String cookiesPath) {
        qualityCombo.getItems().setAll(VideoQualityOption.bestFallback());
        qualityCombo.getSelectionModel().selectFirst();
        if (qualityHintLabel != null) {
            qualityHintLabel.setText("Loading available qualities…");
        }
        Task<List<VideoQualityOption>> task = new Task<>() {
            @Override
            protected List<VideoQualityOption> call() {
                return ytDlpService.fetchAvailableVideoQualities(url, proxyUrl, cookiesPath);
            }
        };
        task.setOnSucceeded(e -> {
            List<VideoQualityOption> options = task.getValue();
            if (options == null || options.isEmpty()) {
                options = List.of(VideoQualityOption.bestFallback());
            }
            qualityCombo.getItems().setAll(options);
            qualityCombo.getSelectionModel().selectFirst();
            if (qualityHintLabel != null) {
                qualityHintLabel.setText(options.size() <= 1
                        ? "Could not list formats — using Best. Update yt-dlp/cookies and re-analyze."
                        : "Found " + (options.size() - 1) + " format(s) for this video (all listed).");
            }
            log("Available qualities: " + options.size());
        });
        task.setOnFailed(e -> {
            qualityCombo.getItems().setAll(VideoQualityOption.bestFallback());
            qualityCombo.getSelectionModel().selectFirst();
            if (qualityHintLabel != null) {
                qualityHintLabel.setText("Quality list unavailable — using Best available.");
            }
        });
        new Thread(task, "quality-fetch").start();
    }

    private void updateSelectionCount() {
        long count = playlistEntries.stream().filter(VideoInfo::isSelected).count();
        selectionCountLabel.setText(count + " of " + playlistEntries.size() + " videos selected");
    }

    private void resetAnalysis() {
        isPlaylist = false;
        playlistEntries.clear();
        showSection(infoBox, false);
        showSection(playlistBox, false);
        showSection(downloadTypeBox, false);
        showSection(videoQualityBox, false);
        showSection(subtitleOptionsBox, false);
        showSection(outputBox, false);
        showSection(downloadBtnBox, false);
        showSection(progressBox, false);
    }

    private void showSection(javafx.scene.Node node, boolean show) {
        node.setVisible(show);
        node.setManaged(show);
    }

    private void log(String message) {
        if (Platform.isFxApplicationThread()) {
            logArea.appendText(message + "\n");
        } else {
            Platform.runLater(() -> logArea.appendText(message + "\n"));
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogPane dialogPane = alert.getDialogPane();
        dialogPane.setStyle("-fx-background-color: #1f2940; -fx-border-color: #2a3a5c;");
        if (dialogPane.lookup(".content.label") != null)
            dialogPane.lookup(".content.label").setStyle("-fx-text-fill: #e8e8e8;");
        alert.showAndWait();
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /* ═══════════════════════════════════════════════════════
        v1.3 - new public hooks for the TabPane host (RootController):
           - snapshotCurrentConfig() : used by Queue tab's "Add with current settings"
           - applySettings(...)       : called when user saves Settings tab
           - showQualityPicker(...)   : Quality Picker for non-YouTube URLs (Feature 3)
       ═══════════════════════════════════════════════════════ */

    /** Snapshot the current UI state as a DownloadConfig (used by Queue tab). */
    public DownloadConfig snapshotCurrentConfig() {
        try { return buildConfig(); }
        catch (Exception e) { log("snapshotCurrentConfig failed: " + e.getMessage()); return null; }
    }

    /** Apply persisted defaults from {@link AppSettings} to the form. */
    public void applySettings(AppSettings s) {
        if (s == null) return;
        // Show/hide proxy section per user toggle.
        showSection(proxyBox, s.enableProxySection);
        // Defaults
        if (s.defaultStrategy != null) strategyCombo.setValue(s.defaultStrategy);
        if (outputDirField.getText() == null || outputDirField.getText().isBlank()) {
            outputDirField.setText(s.defaultOutputDir != null ? s.defaultOutputDir
                    : System.getProperty("user.home") + File.separator + "Downloads");
        }
        // Download type defaults
        if (s.defaultDownloadType != null) {
            checkVideo.setSelected(s.defaultDownloadType == DownloadConfig.DownloadType.VIDEO);
            checkAudio.setSelected(s.defaultDownloadType == DownloadConfig.DownloadType.AUDIO);
            checkSubs.setSelected(s.defaultDownloadType == DownloadConfig.DownloadType.SUBTITLES);
        }
        // Quality
        if (s.defaultQuality != null) {
            // Legacy preset — combo refreshed on Analyze; keep Best until then.
            qualityCombo.getSelectionModel().selectFirst();
        }
        // Subtitles
        if (s.defaultSubtitleLangs != null) subLangField.setText(s.defaultSubtitleLangs);
        if (s.defaultSubFormat == DownloadConfig.SubFormat.VTT) subVtt.setSelected(true);
        else                                                    subSrt.setSelected(true);
        if (s.defaultSubType != null) {
            switch (s.defaultSubType) {
                case ALL    -> subAll.setSelected(true);
                case MANUAL -> subManual.setSelected(true);
                case AUTO   -> subAuto.setSelected(true);
            }
        }
        embedSubsCheck.setSelected(s.defaultEmbedSubs);
        // Cookies default from Settings (Download tab field is optional override)
        if (cookiesField.getText() == null || cookiesField.getText().isBlank()) {
            if (s.defaultCookiesPath != null && !s.defaultCookiesPath.isBlank()) {
                cookiesField.setText(s.defaultCookiesPath);
            }
        }
        // Proxy defaults
        useProxyCheck.setSelected(s.defaultUseProxy);
        if (s.defaultProxyHost != null) proxyHostField.setText(s.defaultProxyHost);
        if (s.defaultProxyPort != null) proxyPortField.setText(s.defaultProxyPort);
        // Strategy-specific
        if (s.invidiousDefaultInstance != null) invidiousInstanceField.setText(s.invidiousDefaultInstance);
        invidiousAutoRotateCheck.setSelected(s.invidiousDefaultAutoRotate);
        if (s.githubDefaultRepo     != null) githubRepoField.setText(s.githubDefaultRepo);
        if (s.githubDefaultWorkflow != null) githubWorkflowField.setText(s.githubDefaultWorkflow);
        if (s.githubDefaultBranch   != null) githubBranchField.setText(s.githubDefaultBranch);
    }

    /** Open the modal Quality Picker dialog and remember the chosen format id. */
    private void showQualityPicker(WebpageExtractor.ExtractResult res) {
        try {
            FXMLLoader loader = new FXMLLoader(HelloApplication.class.getResource("quality-picker.fxml"));
            Parent root = loader.load();
            QualityPickerController qc = loader.getController();
            qc.setResult(res);

            Stage dlg = new Stage();
            dlg.setTitle("Choose a format");
            dlg.initModality(Modality.APPLICATION_MODAL);
            dlg.initOwner(urlField.getScene().getWindow());
            Scene sc = new Scene(root);
            // v1.5: inherit whatever theme the main scene currently has.
            try {
                sc.getStylesheets().addAll(urlField.getScene().getStylesheets());
            } catch (Exception ignored) {}
            dlg.setScene(sc);
            dlg.showAndWait();

            FormatInfo chosen = qc.result();
            if (chosen != null) {
                pickedFormatId    = chosen.getFormatId();
                pickedFormatLabel = chosen.getFormatId()
                        + " / " + chosen.getResolution()
                        + " / " + chosen.getContainer();
                log("Picked format: " + pickedFormatLabel);
            } else {
                log("Quality picker cancelled - the strategy's default format will be used.");
            }
        } catch (Exception e) {
            log("Quality picker error: " + e.getMessage());
        }
    }

    /* ═════════════════════════════════════════════════════════
        v1.5 — Add-to-queue from the Download tab
       ═════════════════════════════════════════════════════════ */

    /** ➕ Add the URL currently in the URL field (with all current settings) to the queue. */
    @FXML
    private void onAddUrlToQueue() {
        if (scheduler == null) {
            showAlert("Scheduler unavailable",
                    "Queue scheduler hasn't been wired yet. Open the Queue tab once, then try again.");
            return;
        }
        DownloadConfig cfg = buildConfig();
        if (cfg == null) return;  // buildConfig already showed a warning

        // For playlists, this single-button path queues the WHOLE thing as one item
        // (yt-dlp will fetch every selected index in one process). Use the 2nd button
        // for per-video queueing.
        QueueItem item = new QueueItem();
        item.label = (contentTitleLabel.getText() != null && !contentTitleLabel.getText().isBlank())
                ? contentTitleLabel.getText() : cfg.getUrl();
        item.config = cfg;
        item.setScheduledAt(System.currentTimeMillis());
        item.ensureTransients();
        scheduler.add(item);
        log("➕ Queued: " + item.label);
    }

    /** ➕ Queue each SELECTED playlist video as its own queue item. */
    @FXML
    private void onAddSelectedToQueue() {
        if (scheduler == null) {
            showAlert("Scheduler unavailable",
                    "Queue scheduler hasn't been wired yet. Open the Queue tab once, then try again.");
            return;
        }
        if (!isPlaylist || playlistEntries.isEmpty()) {
            showAlert("No playlist",
                    "There are no playlist videos here. Use 'Add URL to queue' for a single video.");
            return;
        }
        DownloadConfig base = buildConfig();
        if (base == null) return;

        // We want one queue item per selected video, each pointing at the playlist URL
        // but with a single playlist-index selected. The DownloadConfig already carries
        // playlist-mode + selected indices, so we just shard it.
        int n = 0;
        for (VideoInfo v : playlistEntries) {
            if (!v.isSelected()) continue;
            DownloadConfig perItem = cloneConfigForSingleIndex(base, v.getIndex());
            QueueItem qi = new QueueItem();
            qi.label = "[" + v.getIndex() + "] " + (v.getTitle() != null ? v.getTitle() : cfg(base).getUrl());
            qi.config = perItem;
            qi.setScheduledAt(System.currentTimeMillis());
            qi.ensureTransients();
            scheduler.add(qi);
            n++;
        }
        if (n == 0) {
            showAlert("Nothing selected",
                    "Tick at least one video in the playlist table, then try again.");
            return;
        }
        log("➕ Queued " + n + " playlist video(s).");
    }

    /** Tiny helper so the IDE doesn't whine about parameter naming. */
    private static DownloadConfig cfg(DownloadConfig c) { return c; }

    /** Build a per-video copy of a playlist {@link DownloadConfig}, restricted to one index. */
    private DownloadConfig cloneConfigForSingleIndex(DownloadConfig src, int index) {
        DownloadConfig c = src.duplicate();
        c.setPlaylist(true);
        c.setDownloadAll(false);
        c.setSelectedIndices(java.util.List.of(index));
        return c;
    }
}
