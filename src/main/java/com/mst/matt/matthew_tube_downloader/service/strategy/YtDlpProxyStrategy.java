package com.mst.matt.matthew_tube_downloader.service.strategy;

import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.model.VideoInfo;
import com.mst.matt.matthew_tube_downloader.service.YtDlpService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strategy 3: wrap the existing yt-dlp command line with {@code yt-dlp-proxy}.
 *
 * https://github.com/Petrprogs/yt-dlp-proxy — auto-fetches free public proxies,
 * speed-tests them, and runs yt-dlp through the best one. CLI surface is identical
 * to yt-dlp, so we just swap the binary name.
 */
public class YtDlpProxyStrategy implements DownloadStrategy {

    private static final String BINARY = "yt-dlp-proxy";

    private static final Pattern PROGRESS_PATTERN =
            Pattern.compile("\\[download\\]\\s+(\\d+\\.?\\d*)%");

    private final YtDlpService service;
    private volatile Process currentProcess;

    public YtDlpProxyStrategy(YtDlpService service) {
        this.service = service;
    }

    @Override
    public StrategyType type() { return StrategyType.YT_DLP_PROXY; }

    @Override
    public boolean isAvailable(DownloadConfig config, Consumer<String> logSink) {
        try {
            ProcessBuilder pb = new ProcessBuilder(BINARY, "--help");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (finished) {
                logSink.accept("yt-dlp-proxy found on PATH ✓");
                return true;
            }
            p.destroyForcibly();
        } catch (Exception ignored) {}
        logSink.accept("ERROR: yt-dlp-proxy not found on PATH.");
        logSink.accept("Install: pip install yt-dlp-proxy   then run:  yt-dlp-proxy update");
        logSink.accept("Repo:    https://github.com/Petrprogs/yt-dlp-proxy");
        return false;
    }

    @Override
    public int downloadOne(DownloadConfig config,
                           VideoInfo video,
                           Consumer<String> logSink,
                           BiConsumer<Double, String> progressSink,
                           BooleanSupplier cancelCheck) throws Exception {

        // Re-use YtDlpService to build the canonical yt-dlp command, then swap the binary.
        List<String> baseCmd;
        if (config.isPlaylist() && video != null && video.getIndex() > 0) {
            baseCmd = service.buildSingleItemCommand(config, video.getIndex());
        } else {
            baseCmd = service.buildCommand(config);
        }

        List<String> cmd = new ArrayList<>(baseCmd);
        cmd.set(0, BINARY);  // swap "yt-dlp" -> "yt-dlp-proxy"

        logSink.accept("Command: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        currentProcess = process;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelCheck.getAsBoolean()) {
                    process.destroyForcibly();
                    break;
                }
                Matcher m = PROGRESS_PATTERN.matcher(line);
                if (m.find()) {
                    try {
                        double pct = Double.parseDouble(m.group(1));
                        progressSink.accept(pct / 100.0, String.format("%.1f%%", pct));
                    } catch (NumberFormatException ignored) {}
                }
                logSink.accept(line);
            }
        }
        return process.waitFor();
    }

    @Override
    public void abort() {
        Process p = currentProcess;
        if (p != null && p.isAlive()) p.destroyForcibly();
    }
}
