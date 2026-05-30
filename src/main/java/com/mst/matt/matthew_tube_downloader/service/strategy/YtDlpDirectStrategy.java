package com.mst.matt.matthew_tube_downloader.service.strategy;

import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.model.VideoInfo;
import com.mst.matt.matthew_tube_downloader.service.YtDlpService;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strategy 0 (default): call yt-dlp directly. Wraps the existing {@link YtDlpService}
 * so the behavior is byte-identical to the previous app version.
 */
public class YtDlpDirectStrategy implements DownloadStrategy {

    private static final Pattern PROGRESS_PATTERN =
            Pattern.compile("\\[download\\]\\s+(\\d+\\.?\\d*)%");

    private final YtDlpService service;
    private volatile Process currentProcess;

    public YtDlpDirectStrategy(YtDlpService service) {
        this.service = service;
    }

    @Override
    public StrategyType type() { return StrategyType.YT_DLP_DIRECT; }

    @Override
    public boolean isAvailable(DownloadConfig config, Consumer<String> logSink) {
        boolean ok = service.isYtDlpAvailable();
        if (!ok) logSink.accept("ERROR: yt-dlp binary not found on PATH.");
        return ok;
    }

    @Override
    public int downloadOne(DownloadConfig config,
                           VideoInfo video,
                           Consumer<String> logSink,
                           BiConsumer<Double, String> progressSink,
                           BooleanSupplier cancelCheck) throws Exception {

        List<String> cmd;
        if (config.isPlaylist() && video != null && video.getIndex() > 0) {
            cmd = service.buildSingleItemCommand(config, video.getIndex());
        } else {
            cmd = service.buildCommand(config);
        }

        logSink.accept("Command: " + String.join(" ", cmd));

        return service.executeCommand(cmd, line -> {
            Matcher m = PROGRESS_PATTERN.matcher(line);
            if (m.find()) {
                try {
                    double pct = Double.parseDouble(m.group(1));
                    progressSink.accept(pct / 100.0, String.format("%.1f%%", pct));
                } catch (NumberFormatException ignored) {}
            }
            logSink.accept(line);
        }, p -> currentProcess = p);
    }

    @Override
    public void abort() {
        Process p = currentProcess;
        if (p != null && p.isAlive()) p.destroyForcibly();
    }
}
