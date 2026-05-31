package com.mst.matt.matthew_tube_downloader.service;

import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.model.VideoInfo;
import com.mst.matt.matthew_tube_downloader.service.strategy.*;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;

import java.util.List;

/**
 * JavaFX Task that runs downloads in the background.
 *
 * v1.2 — now dispatches to the user-selected {@link DownloadStrategy}.
 * v1.5 — runs every selected download type (video / audio / subtitles).
 */
public class DownloadTask extends Task<Integer> {

    private final DownloadConfig config;
    private final YtDlpService service;
    private final ObservableList<VideoInfo> playlistEntries;
    private volatile DownloadStrategy strategy;

    public DownloadTask(DownloadConfig config, YtDlpService service, ObservableList<VideoInfo> playlistEntries) {
        this.config = config;
        this.service = service;
        this.playlistEntries = playlistEntries;
    }

    private DownloadStrategy resolveStrategy() {
        StrategyType t = config.getStrategy() != null ? config.getStrategy() : StrategyType.YT_DLP_DIRECT;
        return switch (t) {
            case YT_DLP_DIRECT   -> new YtDlpDirectStrategy(service);
            case INVIDIOUS       -> new InvidiousStrategy();
            case PURE_JAVA       -> new PureJavaStrategy();
            case YT_DLP_PROXY    -> new YtDlpProxyStrategy(service);
            case GITHUB_ACTIONS  -> new GitHubActionsStrategy();
            case SNI_INFO        -> new YtDlpDirectStrategy(service);
        };
    }

    @Override
    protected Integer call() throws Exception {
        updateMessage("Starting download…");
        updateProgress(0, 100);

        strategy = resolveStrategy();
        updateMessage("Strategy: " + strategy.type().getDisplayName());

        if (!strategy.isAvailable(config, this::updateMessage)) {
            updateMessage("Strategy not available — aborting.");
            return 99;
        }

        List<DownloadConfig.DownloadType> types = config.getSelectedDownloadTypes();
        if (types.isEmpty()) {
            updateMessage("No download type selected.");
            return 99;
        }
        updateMessage("Download passes: " + types);

        if (config.isPlaylist() && playlistEntries != null && !playlistEntries.isEmpty()) {
            return downloadPlaylist(types);
        }
        return downloadSingle(types);
    }

    private int downloadPlaylist(List<DownloadConfig.DownloadType> types) throws Exception {
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
            updateMessage("Video " + (i + 1) + "/" + totalVideos + ": " + video.getTitle());

            int exit = MultiTypeDownloadRunner.runAllTypes(
                    config, video, strategy, this::updateMessage,
                    (frac, label) -> {
                        if (frac != null && frac >= 0) updateProgress(frac, 1.0);
                        if (label != null && !label.isBlank()) updateMessage(label);
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

            updateProgress(((double) (i + 1)) / totalVideos * 100, 100);
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

    private int downloadSingle(List<DownloadConfig.DownloadType> types) throws Exception {
        VideoInfo dummy = new VideoInfo(1, "(single video)", "", "", config.getUrl());

        int exit = MultiTypeDownloadRunner.runAllTypes(
                config, dummy, strategy, this::updateMessage,
                (frac, label) -> {
                    if (frac != null && frac >= 0) updateProgress(frac, 1.0);
                    if (label != null && !label.isBlank()) updateMessage(label);
                },
                this::isCancelled);

        if (isCancelled()) {
            updateMessage("Download cancelled.");
            return -1;
        }
        if (exit == 0) {
            updateProgress(100, 100);
            updateMessage(types.size() > 1
                    ? "All " + types.size() + " download types completed successfully!"
                    : "Download completed successfully!");
        } else if (exit > 0) {
            updateProgress(0, 100);
            updateMessage("Download failed (yt-dlp exit code " + exit + "). See log for details.");
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
