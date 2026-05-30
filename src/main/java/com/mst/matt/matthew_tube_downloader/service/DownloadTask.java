package com.mst.matt.matthew_tube_downloader.service;

import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.model.VideoInfo;
import com.mst.matt.matthew_tube_downloader.service.strategy.*;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaFX Task that runs downloads in the background.
 *
 * v1.2 — now dispatches to the user-selected {@link DownloadStrategy}.
 * The original "yt-dlp direct" code path is preserved via {@link YtDlpDirectStrategy},
 * which still uses the unchanged {@link YtDlpService}.
 */
public class DownloadTask extends Task<Integer> {

    private final DownloadConfig config;
    private final YtDlpService service;
    private final ObservableList<VideoInfo> playlistEntries;
    private volatile DownloadStrategy strategy;

    /** Legacy progress regex (still useful for strategies that print yt-dlp lines). */
    @SuppressWarnings("unused")
    private static final Pattern PROGRESS_PATTERN =
            Pattern.compile("\\[download\\]\\s+(\\d+\\.?\\d*)%");

    public DownloadTask(DownloadConfig config, YtDlpService service, ObservableList<VideoInfo> playlistEntries) {
        this.config = config;
        this.service = service;
        this.playlistEntries = playlistEntries;
    }

    /* ───────────────────────── strategy factory ───────────────────────── */

    private DownloadStrategy resolveStrategy() {
        StrategyType t = config.getStrategy() != null ? config.getStrategy() : StrategyType.YT_DLP_DIRECT;
        return switch (t) {
            case YT_DLP_DIRECT   -> new YtDlpDirectStrategy(service);
            case INVIDIOUS       -> new InvidiousStrategy();
            case PURE_JAVA       -> new PureJavaStrategy();
            case YT_DLP_PROXY    -> new YtDlpProxyStrategy(service);
            case GITHUB_ACTIONS  -> new GitHubActionsStrategy();
            case SNI_INFO        -> new YtDlpDirectStrategy(service); // safety fallback
        };
    }

    @Override
    protected Integer call() throws Exception {
        updateMessage("Starting download…");
        updateProgress(0, 100);

        strategy = resolveStrategy();
        updateMessage("Strategy: " + strategy.type().getDisplayName());

        // Pre-flight check
        if (!strategy.isAvailable(config, this::updateMessage)) {
            updateMessage("Strategy not available — aborting.");
            return 99;
        }

        if (config.isPlaylist() && playlistEntries != null && !playlistEntries.isEmpty()) {
            return downloadPlaylist();
        } else {
            return downloadSingle();
        }
    }

    /* ───────────────────────── modes ───────────────────────── */

    private int downloadPlaylist() throws Exception {
        List<VideoInfo> selectedVideos = playlistEntries.stream()
                .filter(VideoInfo::isSelected)
                .toList();

        int totalVideos = selectedVideos.size();
        int completed = 0;
        int failed = 0;

        for (int i = 0; i < selectedVideos.size(); i++) {
            if (isCancelled()) break;
            VideoInfo video = selectedVideos.get(i);

            Platform.runLater(() -> {
                video.setStatus("Downloading…");
                video.setProgress(0.0);
            });
            updateMessage("Downloading " + (i + 1) + "/" + totalVideos + ": " + video.getTitle());

            try {
                int exit = strategy.downloadOne(
                        config, video,
                        this::updateMessage,
                        (frac, label) -> {
                            if (frac != null && frac >= 0) {
                                final double f = frac;
                                Platform.runLater(() -> {
                                    video.setProgress(f);
                                    video.setStatus(label);
                                });
                            } else {
                                Platform.runLater(() -> video.setStatus(label));
                            }
                        },
                        this::isCancelled);

                if (exit == 0) {
                    completed++;
                    Platform.runLater(() -> {
                        video.setStatus("✓ Done");
                        video.setProgress(1.0);
                        video.setSelected(false);
                    });
                } else if (exit == -1) {
                    Platform.runLater(() -> video.setStatus("✖ Cancelled"));
                    break;
                } else {
                    failed++;
                    Platform.runLater(() -> video.setStatus("✗ Error (" + exit + ")"));
                }
            } catch (Exception e) {
                failed++;
                String msg = e.getMessage();
                Platform.runLater(() -> video.setStatus("✗ " + (msg != null ? msg : "error")));
            }

            updateProgress(((double)(i + 1)) / totalVideos * 100, 100);
        }

        if (isCancelled()) {
            updateMessage("Download cancelled.");
            return -1;
        }

        String summary = String.format("Completed: %d/%d videos (failed: %d)", completed, totalVideos, failed);
        updateMessage(summary);
        updateProgress(100, 100);
        return failed > 0 ? 1 : 0;
    }

    private int downloadSingle() throws Exception {
        // For single-video mode, synthesize a one-item VideoInfo so strategies have a uniform input.
        VideoInfo dummy = new VideoInfo(1, "(single video)", "", "", config.getUrl());

        int exit = strategy.downloadOne(
                config, dummy,
                this::updateMessage,
                (frac, label) -> {
                    if (frac != null && frac >= 0) updateProgress(frac, 1.0);
                    updateMessage(label);
                },
                this::isCancelled);

        if (isCancelled()) {
            updateMessage("Download cancelled.");
            return -1;
        }
        if (exit == 0) {
            updateProgress(100, 100);
            updateMessage("Download completed successfully!");
        } else {
            updateMessage("Download finished with exit code: " + exit);
        }
        return exit;
    }

    @Override
    protected void cancelled() {
        super.cancelled();
        DownloadStrategy s = strategy;
        if (s != null) s.abort();
    }
}
