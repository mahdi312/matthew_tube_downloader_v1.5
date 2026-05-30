package com.mst.matt.matthew_tube_downloader.service;

import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.model.VideoInfo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Service layer that wraps yt-dlp CLI commands.
 *
 * v1.5 changes:
 *   • All process output is now read as UTF-8 (was platform default — broke
 *     Farsi / Arabic / CJK titles on Windows cp1252).
 *   • {@link #getVideoTitle} and {@link #detectPlaylistTitle} skip yt-dlp
 *     WARNING / ERROR lines so the UI never shows
 *     "Detected single video: WARNING: ..." again.
 *   • {@code --no-warnings} added to metadata-only calls.
 *   • New {@link #updateYtDlp(Consumer)} helper for self-update via
 *     {@code yt-dlp -U}.
 */
public class YtDlpService {

    private static final String YT_DLP = "yt-dlp";

    /** A line yt-dlp emits when it falls back / fails — we never want this
     *  shown as a title. */
    private static boolean isNoiseLine(String line) {
        if (line == null) return true;
        String t = line.trim();
        if (t.isEmpty()) return true;
        // Common yt-dlp diagnostic prefixes
        return t.startsWith("WARNING:")
            || t.startsWith("ERROR:")
            || t.startsWith("[debug]")
            || t.startsWith("[info]")
            || t.startsWith("[download]")
            || t.startsWith("[youtube]")
            || t.startsWith("[generic]")
            || t.startsWith("Deprecation");
    }

    /**
     * Check if yt-dlp is installed and accessible.
     */
    public boolean isYtDlpAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(YT_DLP, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Get yt-dlp version string.
     */
    public String getYtDlpVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder(YT_DLP, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String version = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(10, TimeUnit.SECONDS);
            return version;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * v1.5: Run {@code yt-dlp -U} to self-update the binary. Useful when YouTube
     * changes its player and the user starts seeing
     * "No title found in player responses" warnings.
     */
    public boolean updateYtDlp(Consumer<String> outputConsumer) {
        try {
            outputConsumer.accept("Running yt-dlp -U (self-update)…");
            ProcessBuilder pb = new ProcessBuilder(YT_DLP, "-U");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) outputConsumer.accept(line);
            }
            int code = p.waitFor();
            if (code == 0) { outputConsumer.accept("yt-dlp -U finished OK."); return true; }
            outputConsumer.accept("yt-dlp -U exited with code " + code + " — try `pip install -U yt-dlp` instead.");
        } catch (Exception e) {
            outputConsumer.accept("yt-dlp -U failed: " + e.getMessage());
        }
        // Fallback: pip upgrade
        return installYtDlp(outputConsumer);
    }

    /**
     * Try to install yt-dlp using pip.
     * Returns true if installation succeeded.
     */
    public boolean installYtDlp(Consumer<String> outputConsumer) {
        try {
            outputConsumer.accept("Attempting to install yt-dlp via pip...");
            ProcessBuilder pb = new ProcessBuilder("pip", "install", "-U", "yt-dlp");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputConsumer.accept(line);
                }
            }
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                outputConsumer.accept("yt-dlp installed successfully!");
                return true;
            }
        } catch (Exception e) {
            outputConsumer.accept("pip install failed: " + e.getMessage());
        }

        // Try pip3
        try {
            outputConsumer.accept("Trying pip3...");
            ProcessBuilder pb = new ProcessBuilder("pip3", "install", "-U", "yt-dlp");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputConsumer.accept(line);
                }
            }
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                outputConsumer.accept("yt-dlp installed successfully via pip3!");
                return true;
            }
        } catch (Exception e) {
            outputConsumer.accept("pip3 install failed: " + e.getMessage());
        }

        // Try winget on Windows
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            try {
                outputConsumer.accept("Trying winget...");
                ProcessBuilder pb = new ProcessBuilder("winget", "install", "yt-dlp.yt-dlp");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outputConsumer.accept(line);
                    }
                }
                int exitCode = p.waitFor();
                if (exitCode == 0) {
                    outputConsumer.accept("yt-dlp installed successfully via winget!");
                    return true;
                }
            } catch (Exception e) {
                outputConsumer.accept("winget install failed: " + e.getMessage());
            }
        }

        outputConsumer.accept("Could not auto-install yt-dlp. Please install manually:");
        outputConsumer.accept("  pip install yt-dlp");
        outputConsumer.accept("  OR download from: https://github.com/yt-dlp/yt-dlp/releases");
        return false;
    }

    /**
     * Detect whether a URL is a playlist or single video.
     * Returns the playlist title if it's a playlist, or null if single video.
     *
     * v1.5: UTF-8 decoding + skips WARNING/ERROR lines so Persian / Arabic
     * playlist titles render correctly and noise lines never get mis-promoted
     * to "title".
     */
    public String detectPlaylistTitle(String url, String proxyUrl) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(YT_DLP);
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            cmd.add("--proxy");
            cmd.add(proxyUrl);
        }
        cmd.add("--no-warnings");
        cmd.add("--flat-playlist");
        cmd.add("--print");
        cmd.add("%(playlist_title)s");
        cmd.add(url);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor(60, TimeUnit.SECONDS);

        if (output.isEmpty()) return null;
        // Return the first usable (non-noise, non-NA) line.
        for (String line : output.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.equals("NA") || isNoiseLine(t)) continue;
            return t;
        }
        return null;
    }

    /**
     * Get single video title.
     *
     * v1.5: UTF-8 + filters out WARNING / ERROR lines. Previously a
     * "WARNING: No title found in player responses; falling back ..." line
     * was returned verbatim as the title.
     */
    public String getVideoTitle(String url, String proxyUrl) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(YT_DLP);
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            cmd.add("--proxy");
            cmd.add(proxyUrl);
        }
        cmd.add("--no-warnings");
        cmd.add("--get-title");
        cmd.add(url);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor(60, TimeUnit.SECONDS);

        if (output.isEmpty()) return "(title unavailable)";
        for (String line : output.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || isNoiseLine(t)) continue;
            return t;
        }
        return "(title unavailable)";
    }

    /**
     * Fetch playlist entries (flat, fast).
     *
     * v1.5: UTF-8 stream reader (was platform default which corrupted Farsi).
     */
    public List<VideoInfo> fetchPlaylistEntries(String url, String proxyUrl) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(YT_DLP);
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            cmd.add("--proxy");
            cmd.add(proxyUrl);
        }
        cmd.add("--no-warnings");
        cmd.add("--flat-playlist");
        cmd.add("--print");
        cmd.add("%(playlist_index)s\t%(title)s\t%(id)s\t%(duration_string)s\t%(url)s");
        cmd.add(url);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        List<VideoInfo> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || isNoiseLine(line)) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length >= 2) {
                    int idx;
                    try {
                        idx = Integer.parseInt(parts[0].trim());
                    } catch (NumberFormatException e) {
                        idx = entries.size() + 1;
                    }
                    String title = parts.length > 1 ? parts[1] : "Unknown";
                    // Some titles come through as the literal "NA" — give a kinder fallback.
                    if (title == null || title.isBlank() || "NA".equals(title)) {
                        title = "(untitled #" + idx + ")";
                    }
                    String id = parts.length > 2 ? parts[2] : "";
                    String duration = parts.length > 3 ? parts[3] : "";
                    String videoUrl = parts.length > 4 ? parts[4] : "";
                    entries.add(new VideoInfo(idx, title, id, duration, videoUrl));
                }
            }
        }
        p.waitFor(120, TimeUnit.SECONDS);
        return entries;
    }

    /**
     * Build the full yt-dlp command for downloading a single playlist item.
     */
    public List<String> buildSingleItemCommand(DownloadConfig config, int playlistIndex) {
        List<String> cmd = new ArrayList<>();
        cmd.add(YT_DLP);

        // Proxy
        String proxyUrl = config.getProxyUrl();
        if (proxyUrl != null) {
            cmd.add("--proxy");
            cmd.add(proxyUrl);
        }

        switch (config.getDownloadType()) {
            case VIDEO -> buildVideoCommand(cmd, config);
            case AUDIO -> buildAudioCommand(cmd, config);
            case SUBTITLES -> buildSubtitlesCommand(cmd, config);
        }

        // Cookies
        if (config.getCookiesPath() != null && !config.getCookiesPath().isBlank()) {
            Path cookiesPath = Path.of(config.getCookiesPath());
            if (Files.exists(cookiesPath)) {
                cmd.add("--cookies");
                cmd.add(config.getCookiesPath());
            }
        }

        // Single playlist item
        cmd.add("--playlist-items");
        cmd.add(String.valueOf(playlistIndex));

        // Safety flags
        addSafetyFlags(cmd);

        // Output template
        cmd.add("-o");
        String outputDir = config.getOutputDir() != null ? config.getOutputDir() : ".";
        cmd.add(outputDir + "/%(playlist_index)s-%(title)s.%(ext)s");

        // URL
        cmd.add(config.getUrl());

        return cmd;
    }

    /**
     * Build the full yt-dlp command for downloading (single video, no playlist).
     */
    public List<String> buildCommand(DownloadConfig config) {
        List<String> cmd = new ArrayList<>();
        cmd.add(YT_DLP);

        // Proxy
        String proxyUrl = config.getProxyUrl();
        if (proxyUrl != null) {
            cmd.add("--proxy");
            cmd.add(proxyUrl);
        }

        switch (config.getDownloadType()) {
            case VIDEO -> buildVideoCommand(cmd, config);
            case AUDIO -> buildAudioCommand(cmd, config);
            case SUBTITLES -> buildSubtitlesCommand(cmd, config);
        }

        // Cookies
        if (config.getCookiesPath() != null && !config.getCookiesPath().isBlank()) {
            Path cookiesPath = Path.of(config.getCookiesPath());
            if (Files.exists(cookiesPath)) {
                cmd.add("--cookies");
                cmd.add(config.getCookiesPath());
            }
        }

        // Playlist items (for non-per-video mode)
        String playlistItems = config.getPlaylistItemsArg();
        if (playlistItems != null) {
            cmd.add("--playlist-items");
            cmd.add(playlistItems);
        }

        // Safety flags
        addSafetyFlags(cmd);

        // Output template
        cmd.add("-o");
        String outputDir = config.getOutputDir() != null ? config.getOutputDir() : ".";
        cmd.add(outputDir + "/%(playlist_index)s-%(title)s.%(ext)s");

        // URL
        cmd.add(config.getUrl());

        return cmd;
    }

    private void addSafetyFlags(List<String> cmd) {
        cmd.add("--extractor-args");
        cmd.add("youtubetab:skip=authcheck");
        cmd.add("-i");
        cmd.add("--sleep-interval");
        cmd.add("12");
        cmd.add("--max-sleep-interval");
        cmd.add("30");
        cmd.add("--retries");
        cmd.add("20");
        cmd.add("--fragment-retries");
        cmd.add("10");
        cmd.add("--socket-timeout");
        cmd.add("90");
        cmd.add("--newline");
        // v1.3 — enable resume-from-.part so the scheduler's pause/resume works.
        cmd.add("--continue");
        // v1.5 — force UTF-8 console encoding so Persian/Arabic titles aren't
        // mojibake'd when piped back through ProcessBuilder.
        cmd.add("--encoding");
        cmd.add("utf-8");
    }

    private void buildVideoCommand(List<String> cmd, DownloadConfig config) {
        cmd.add("-f");
        cmd.add(config.getFormatString());
        cmd.add("--embed-metadata");

        if (config.getSubtitleLanguages() != null && !config.getSubtitleLanguages().isBlank()
                && !config.getSubtitleLanguages().equalsIgnoreCase("none")) {
            cmd.add("--write-subs");
            cmd.add("--write-auto-subs");
            cmd.add("--sub-langs");
            cmd.add(config.getSubtitleLanguages());
            cmd.add("--convert-subs");
            cmd.add("srt");
            if (config.isEmbedSubtitles()) {
                cmd.add("--embed-subs");
            }
        }
    }

    private void buildAudioCommand(List<String> cmd, DownloadConfig config) {
        cmd.add("-f");
        cmd.add("bestaudio");
        cmd.add("--embed-metadata");
    }

    private void buildSubtitlesCommand(List<String> cmd, DownloadConfig config) {
        cmd.add("--skip-download");

        switch (config.getSubType()) {
            case ALL -> { cmd.add("--write-subs"); cmd.add("--write-auto-subs"); }
            case MANUAL -> cmd.add("--write-subs");
            case AUTO -> cmd.add("--write-auto-subs");
        }

        cmd.add("--sub-langs");
        cmd.add(config.getSubtitleLanguages() != null ? config.getSubtitleLanguages() : "en");
        cmd.add("--convert-subs");
        cmd.add(config.getSubFormat() == DownloadConfig.SubFormat.VTT ? "vtt" : "srt");
    }

    /**
     * Execute a command, streaming output line by line to the consumer.
     * Returns the process exit code.
     *
     * v1.5: UTF-8 stream reader.
     */
    public int executeCommand(List<String> cmd, Consumer<String> outputConsumer,
                              Consumer<Process> processConsumer) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        if (processConsumer != null) {
            processConsumer.accept(process);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputConsumer.accept(line);
            }
        }

        return process.waitFor();
    }
}
