package com.mst.matt.matthew_tube_downloader.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.model.VideoInfo;
import com.mst.matt.matthew_tube_downloader.model.VideoQualityOption;
import com.mst.matt.matthew_tube_downloader.service.dependency.PotProviderHelper;
import com.mst.matt.matthew_tube_downloader.service.settings.AppSettings;
import com.mst.matt.matthew_tube_downloader.service.settings.SettingsManager;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern YOUTUBE_VIDEO_ID = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?.*v=|embed/|shorts/|v/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private static final String DEFAULT_INVIDIOUS = "https://yewtu.be";

    private static final List<String> INVIDIOUS_FALLBACKS = List.of(
            "https://yewtu.be",
            "https://invidious.nerdvpn.de",
            "https://invidious.privacydev.net",
            "https://inv.nadeko.net",
            "https://invidious.f5.si"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** Ensure yt-dlp (Python) emits UTF-8 on stdout — critical on Windows cp1252. */
    private static void configureUtf8Process(ProcessBuilder pb) {
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
    }

    private static boolean isNoiseLine(String line) {
        if (line == null) return true;
        String t = stripAnsi(line).trim();
        if (t.isEmpty()) return true;
        String lower = t.toLowerCase();
        // Common yt-dlp / Deno / PO-provider diagnostic lines (stdout may include ANSI codes)
        return t.startsWith("WARNING:")
            || t.startsWith("ERROR:")
            || lower.contains("error:")
            || lower.contains("could not resolve")
            || lower.contains("deno install")
            || lower.contains("node_modules/")
            || lower.contains("traceback (most recent call last)")
            || t.startsWith("[debug]")
            || t.startsWith("[info]")
            || t.startsWith("[download]")
            || t.startsWith("[youtube]")
            || t.startsWith("[generic]")
            || t.startsWith("Deprecation");
    }

    /** Remove ANSI colour / style codes and orphaned {@code [33m} fragments. */
    public static String stripAnsi(String text) {
        if (text == null) return "";
        String s = text.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "");
        s = s.replaceAll("\\[[0-9;]*m", "");
        return s;
    }

    /** True when the URL explicitly references a YouTube playlist (not a lone watch link). */
    public static boolean urlLooksLikeExplicitPlaylist(String url) {
        if (url == null || url.isBlank()) return false;
        String u = url.toLowerCase();
        return u.contains("list=") || u.contains("/playlist");
    }

    /** Strip {@code &t=3s} and similar params; they can confuse single-video metadata. */
    public static String normalizeYoutubeUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        u = u.replaceAll("([?&])(t|start|end|pp|si|feature)=[^&]*", "");
        u = u.replaceAll("\\?&", "?").replaceAll("[?&]+$", "");
        return u;
    }

    public static String extractYoutubeVideoId(String url) {
        if (url == null) return null;
        Matcher m = YOUTUBE_VIDEO_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static void appendProxyAndCookies(List<String> cmd, String proxyUrl, String cookiesPath) {
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            cmd.add("--proxy");
            cmd.add(proxyUrl);
        }
        if (cookiesPath != null && !cookiesPath.isBlank()) {
            Path cookies = Path.of(cookiesPath);
            if (Files.exists(cookies)) {
                cmd.add("--cookies");
                cmd.add(cookiesPath);
            }
        }
    }

    /** Flags shared by all metadata-only yt-dlp calls (analyze / title / playlist list). */
    private static void appendMetadataFlags(List<String> cmd) {
        cmd.add("--no-warnings");
        cmd.add("--encoding");
        cmd.add("utf-8");
        cmd.add("--ignore-no-formats-error");
        appendPotProviderFlags(cmd);
    }

    private static void appendPotProviderFlags(List<String> cmd) {
        try {
            PotProviderHelper.appendYoutubeExtractorArgs(cmd, SettingsManager.load());
        } catch (Exception ignored) {}
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
        return detectPlaylistTitle(url, proxyUrl, null);
    }

    public String detectPlaylistTitle(String url, String proxyUrl, String cookiesPath) throws Exception {
        url = normalizeYoutubeUrl(url);
        List<String> cmd = new ArrayList<>();
        cmd.add(YT_DLP);
        appendProxyAndCookies(cmd, proxyUrl, cookiesPath);
        appendMetadataFlags(cmd);
        if (!urlLooksLikeExplicitPlaylist(url)) {
            cmd.add("--no-playlist");
        }
        cmd.add("--flat-playlist");
        cmd.add("--print");
        cmd.add("%(playlist_title)s");
        cmd.add(url);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        configureUtf8Process(pb);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor(60, TimeUnit.SECONDS);

        if (output.isEmpty()) return null;
        // Return the first usable (non-noise, non-NA) line.
        for (String line : output.split("\\R")) {
            String t = stripAnsi(line).trim();
            if (t.isEmpty() || t.equals("NA") || isNoiseLine(t)) continue;
            if (!isUsableTitle(t)) continue;
            return t;
        }
        return null;
    }

    /**
     * Get single video title.
     *
     * Playlist rows already use {@link #fetchPlaylistEntries} and show Persian
     * titles correctly. Single-video analyze used {@code --no-playlist}
     * {@code --flat-playlist --print "%(title)s"}, which often prints nothing
     * (hence "(title unavailable)") even when the playlist-style query works.
     */
    public String getVideoTitle(String url, String proxyUrl) throws Exception {
        return getVideoTitle(url, proxyUrl, null, null);
    }

    public String getVideoTitle(String url, String proxyUrl, String cookiesPath, String invidiousInstance)
            throws Exception {
        url = normalizeYoutubeUrl(url);
        String videoId = extractYoutubeVideoId(url);

        // 1) YouTube oEmbed — fast, no yt-dlp; works for many Persian titles.
        if (videoId != null) {
            String viaOembed = fetchTitleViaOembed(videoId);
            if (viaOembed != null) return viaOembed;
        }

        // 2) yt-dlp --dump-single-json (robust; avoids Deno noise on stdout from --print).
        String viaJson = fetchTitleViaDumpJson(url, proxyUrl, cookiesPath);
        if (viaJson != null) return viaJson;

        // 3) yt-dlp --print (may be polluted when PO script runs — validate title).
        String viaPrint = runYtDlpForFirstTitleLine(url, proxyUrl, cookiesPath, true);
        if (viaPrint != null) return viaPrint;

        // 4) Flat-playlist row — only for explicit playlist URLs.
        if (urlLooksLikeExplicitPlaylist(url)) {
            for (VideoInfo entry : fetchPlaylistEntries(url, proxyUrl, cookiesPath)) {
                String title = entry.getTitle();
                if (isUsableTitle(title)) return title;
            }
        }

        // 5) Legacy --get-title.
        String viaGetTitle = runYtDlpForFirstTitleLine(url, proxyUrl, cookiesPath, false);
        if (viaGetTitle != null) return viaGetTitle;

        // 6) Invidious API (rotate instances).
        if (videoId != null) {
            String viaInvidious = fetchTitleViaInvidious(videoId, invidiousInstance);
            if (viaInvidious != null) return viaInvidious;
        }

        return "(title unavailable)";
    }

    /** Parse {@code --dump-single-json} stdout and return the {@code title} field. */
    private String fetchTitleViaDumpJson(String url, String proxyUrl, String cookiesPath) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(YT_DLP);
        appendProxyAndCookies(cmd, proxyUrl, cookiesPath);
        appendMetadataFlags(cmd);
        cmd.add("--no-playlist");
        cmd.add("--skip-download");
        cmd.add("--dump-single-json");
        cmd.add(url);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        configureUtf8Process(pb);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || isNoiseLine(t) || !t.startsWith("{")) continue;
                JsonObject root = JsonParser.parseString(t).getAsJsonObject();
                if (!root.has("title") || root.get("title").isJsonNull()) continue;
                String title = root.get("title").getAsString();
                if (isUsableTitle(title)) return title;
            }
        } finally {
            p.waitFor(90, TimeUnit.SECONDS);
        }
        return null;
    }

    /**
     * List video heights/formats the URL actually offers (2K, 4K, …) via
     * {@code --dump-single-json}. Falls back to {@link VideoQualityOption#bestFallback()}.
     */
    public List<VideoQualityOption> fetchAvailableVideoQualities(String url, String proxyUrl,
                                                                  String cookiesPath) {
        List<VideoQualityOption> fallback = List.of(VideoQualityOption.bestFallback());
        try {
            url = normalizeYoutubeUrl(url);
            List<String> cmd = new ArrayList<>();
            cmd.add(YT_DLP);
            appendProxyAndCookies(cmd, proxyUrl, cookiesPath);
            appendMetadataFlags(cmd);
            cmd.add("--no-playlist");
            cmd.add("--skip-download");
            cmd.add("--dump-single-json");
            cmd.add(url);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            configureUtf8Process(pb);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            JsonObject root = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty() || isNoiseLine(t) || !t.startsWith("{")) continue;
                    root = JsonParser.parseString(t).getAsJsonObject();
                    break;
                }
            } finally {
                p.waitFor(120, TimeUnit.SECONDS);
            }
            if (root == null || !root.has("formats")) return fallback;
            List<VideoQualityOption> parsed = parseVideoQualityOptions(root.getAsJsonArray("formats"));
            return parsed.isEmpty() ? fallback : parsed;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static List<VideoQualityOption> parseVideoQualityOptions(JsonArray formats) {
        List<VideoQualityOption> out = new ArrayList<>();
        out.add(VideoQualityOption.bestFallback());

        List<JsonObject> videoFormats = new ArrayList<>();
        for (JsonElement el : formats) {
            JsonObject f = el.getAsJsonObject();
            if (!isListableVideoFormat(f)) continue;
            videoFormats.add(f);
        }

        videoFormats.sort(Comparator
                .comparingInt((JsonObject f) -> jsonInt(f, "height", 0)).reversed()
                .thenComparingInt(f -> jsonInt(f, "fps", 0)).reversed()
                .thenComparingDouble(f -> jsonDouble(f, "tbr", 0)).reversed()
                .thenComparing(f -> jsonStr(f, "format_id", "")));

        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (JsonObject f : videoFormats) {
            String formatId = jsonStr(f, "format_id", "").trim();
            if (formatId.isEmpty() || !seen.add(formatId)) continue;
            int height = jsonInt(f, "height", 0);
            out.add(new VideoQualityOption(
                    buildFormatLabel(f),
                    buildFormatDownloadSpec(f, formatId),
                    height,
                    formatId));
        }
        return out;
    }

    private static boolean isListableVideoFormat(JsonObject f) {
        String vcodec = jsonStr(f, "vcodec", "none");
        if (vcodec == null || "none".equals(vcodec) || vcodec.startsWith("none")) return false;
        if ("images".equals(jsonStr(f, "ext", ""))) return false;
        return jsonInt(f, "height", 0) > 0;
    }

    private static String buildFormatDownloadSpec(JsonObject f, String formatId) {
        String acodec = jsonStr(f, "acodec", "none");
        boolean hasAudio = acodec != null && !"none".equals(acodec) && !acodec.startsWith("none");
        return hasAudio ? formatId : formatId + "+bestaudio/bestaudio/best";
    }

    private static String buildFormatLabel(JsonObject f) {
        int height = jsonInt(f, "height", 0);
        int fps = jsonInt(f, "fps", 0);
        String ext = jsonStr(f, "ext", "mp4");
        String note = jsonStr(f, "format_note", "");
        String vcodec = jsonStr(f, "vcodec", "");
        String formatId = jsonStr(f, "format_id", "");
        String tier = height >= 2160 ? "4K" : height >= 1440 ? "2K" : null;

        StringBuilder sb = new StringBuilder();
        sb.append(height).append("p");
        if (fps > 0 && fps != 30) sb.append("@").append(fps);
        if (tier != null) sb.append(" (").append(tier).append(")");
        sb.append(" — ").append(ext);
        if (note != null && !note.isBlank()) sb.append(" · ").append(note);
        if (vcodec != null && !vcodec.isBlank() && !"none".equals(vcodec)) {
            int dot = vcodec.indexOf('.');
            sb.append(" · ").append(dot > 0 ? vcodec.substring(0, dot) : vcodec);
        }
        sb.append(" [id ").append(formatId).append("]");
        return sb.toString();
    }

    private static String jsonStr(JsonObject o, String key, String def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        return o.get(key).getAsString();
    }

    private static int jsonInt(JsonObject o, String key, int def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsInt(); } catch (Exception e) { return def; }
    }

    private static double jsonDouble(JsonObject o, String key, double def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsDouble(); } catch (Exception e) { return def; }
    }

    /** {@code https://www.youtube.com/oembed} — public metadata, UTF-8 JSON. */
    public String fetchTitleViaOembed(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;
        try {
            String watch = "https://www.youtube.com/watch?v=" + videoId;
            String api = "https://www.youtube.com/oembed?url="
                    + URLEncoder.encode(watch, StandardCharsets.UTF_8) + "&format=json";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(api))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "application/json")
                    .header("Accept-Language", "fa,en;q=0.9")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) return null;
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!root.has("title") || root.get("title").isJsonNull()) return null;
            String title = root.get("title").getAsString();
            return isUsableTitle(title) ? title : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Resolve title via Invidious {@code /api/v1/videos/<id>} (UTF-8 JSON). */
    public String fetchTitleViaInvidious(String videoId, String invidiousInstance) {
        if (videoId == null || videoId.isBlank()) return null;
        List<String> bases = new ArrayList<>();
        if (invidiousInstance != null && !invidiousInstance.isBlank()) {
            bases.add(invidiousInstance.trim().replaceAll("/+$", ""));
        }
        for (String fb : INVIDIOUS_FALLBACKS) {
            if (!bases.contains(fb)) bases.add(fb);
        }
        for (String base : bases) {
            String title = fetchTitleViaInvidiousOnce(base, videoId);
            if (title != null) return title;
        }
        return null;
    }

    private String fetchTitleViaInvidiousOnce(String base, String videoId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/videos/" + videoId))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "MatthewTubeDownloader/1.5")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) return null;
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!root.has("title") || root.get("title").isJsonNull()) return null;
            String title = root.get("title").getAsString();
            return isUsableTitle(title) ? title : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean isUsableTitle(String title) {
        if (title == null) return false;
        String t = stripAnsi(title).trim();
        if (t.isBlank() || "NA".equals(t)) return false;
        if (t.startsWith("(untitled")) return false;
        if (t.length() < 2) return false;
        String lower = t.toLowerCase();
        if (lower.contains("error:") || lower.contains("could not resolve")) return false;
        if (lower.contains("deno") && lower.contains("install")) return false;
        if (lower.contains("sign in to confirm")) return false;
        if (lower.contains("file:///") || lower.contains(".ts:") || lower.contains("generate_once")) return false;
        if (lower.contains("bgutil-ytdlp-pot-provider") || lower.contains("transport error")) return false;
        // Leftover ANSI fragments / stack-trace coordinates (e.g. "0m:[33m3:[33m25")
        if (t.matches("(?s).*[\\[\\]0-9;]*m.*") && !t.matches(".*\\p{L}.*")) return false;
        if (t.matches("^[0-9:m\\[\\];\\s]+$")) return false;
        return true;
    }

    /**
     * @param usePrintTemplate if true, {@code --print "%(title)s"}; else {@code --get-title}
     */
    private String runYtDlpForFirstTitleLine(String url, String proxyUrl, String cookiesPath,
                                             boolean usePrintTemplate) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(YT_DLP);
        appendProxyAndCookies(cmd, proxyUrl, cookiesPath);
        appendMetadataFlags(cmd);
        cmd.add("--no-playlist");
        cmd.add("--skip-download");
        if (usePrintTemplate) {
            cmd.add("--print");
            cmd.add("%(title)s");
        } else {
            cmd.add("--get-title");
        }
        cmd.add(url);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        configureUtf8Process(pb);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String t = stripAnsi(line).trim();
                if (t.isEmpty() || isNoiseLine(t) || "NA".equals(t)) continue;
                if (!isUsableTitle(t)) continue;
                return t;
            }
        } finally {
            p.waitFor(90, TimeUnit.SECONDS);
        }
        return null;
    }

    /**
     * Fetch playlist entries (flat, fast).
     *
     * v1.5: UTF-8 stream reader (was platform default which corrupted Farsi).
     */
    public List<VideoInfo> fetchPlaylistEntries(String url, String proxyUrl) throws Exception {
        return fetchPlaylistEntries(url, proxyUrl, null);
    }

    public List<VideoInfo> fetchPlaylistEntries(String url, String proxyUrl, String cookiesPath) throws Exception {
        url = normalizeYoutubeUrl(url);
        List<String> cmd = new ArrayList<>();
        cmd.add(YT_DLP);
        appendProxyAndCookies(cmd, proxyUrl, cookiesPath);
        appendMetadataFlags(cmd);
        if (!urlLooksLikeExplicitPlaylist(url)) {
            cmd.add("--no-playlist");
        }
        cmd.add("--flat-playlist");
        cmd.add("--print");
        cmd.add("%(playlist_index)s\t%(title)s\t%(id)s\t%(duration_string)s\t%(url)s");
        cmd.add(url);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        configureUtf8Process(pb);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        List<VideoInfo> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                VideoInfo parsed = parseFlatPlaylistLine(line, entries.size() + 1);
                if (parsed != null) entries.add(parsed);
            }
        }
        p.waitFor(120, TimeUnit.SECONDS);
        return entries;
    }

    /**
     * Parse one {@code --flat-playlist --print} line. Single-video URLs sometimes emit
     * only the title (no tab-separated fields); playlist URLs use index/title/id/…
     */
    private VideoInfo parseFlatPlaylistLine(String line, int fallbackIndex) {
        line = stripAnsi(line);
        if (line == null || line.trim().isEmpty() || isNoiseLine(line)) return null;

        String[] parts = line.split("\t", -1);
        if (parts.length == 1) {
            String titleOnly = parts[0].trim();
            if (!isUsableTitle(titleOnly)) return null;
            return new VideoInfo(fallbackIndex, titleOnly, "", "", "");
        }
        if (parts.length < 2) return null;

        int idx;
        try {
            idx = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            idx = fallbackIndex;
        }
        String title = parts[1].trim();
        if (!isUsableTitle(title)) {
            title = "(untitled #" + idx + ")";
        }
        String id = parts.length > 2 ? parts[2].trim() : "";
        String duration = parts.length > 3 ? parts[3].trim() : "";
        String videoUrl = parts.length > 4 ? parts[4].trim() : "";
        return new VideoInfo(idx, title, id, duration, videoUrl);
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
        addSafetyFlags(cmd, config.getDownloadType());

        // Output template — suffix per type so multi-pass downloads don't overwrite.
        cmd.add("-o");
        String outputDir = config.getOutputDir() != null ? config.getOutputDir() : ".";
        String typeTag = switch (config.getDownloadType()) {
            case AUDIO -> " [audio]";
            case SUBTITLES -> " [subs]";
            default -> "";
        };
        cmd.add(outputDir + "/" + outputNameTemplate(config, typeTag) + ".%(ext)s");

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
        addSafetyFlags(cmd, config.getDownloadType());

        // Output template — suffix per type so multi-pass downloads don't overwrite.
        cmd.add("-o");
        String outputDir = config.getOutputDir() != null ? config.getOutputDir() : ".";
        String typeTag = switch (config.getDownloadType()) {
            case AUDIO -> " [audio]";
            case SUBTITLES -> " [subs]";
            default -> "";
        };
        cmd.add(outputDir + "/" + outputNameTemplate(config, typeTag) + ".%(ext)s");

        // URL
        cmd.add(config.getUrl());

        return cmd;
    }

    /** Single video: {@code %(title)s}; playlist: {@code %(playlist_index)s-%(title)s}. */
    private static String outputNameTemplate(DownloadConfig config, String typeTag) {
        if (config.isPlaylist()) {
            return "%(playlist_index)s-%(title)s" + typeTag;
        }
        return "%(title)s" + typeTag;
    }

    private void addSafetyFlags(List<String> cmd, DownloadConfig.DownloadType type) {
        try {
            PotProviderHelper.appendYoutubeExtractorArgs(cmd, SettingsManager.load());
        } catch (Exception ignored) {}

        cmd.add("--remote-components");
        cmd.add("ejs:github");
        cmd.add("--js-runtimes");
        cmd.add("deno,node");
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
        cmd.add("--continue");
        cmd.add("--encoding");
        cmd.add("utf-8");
        if (type != DownloadConfig.DownloadType.AUDIO) {
            cmd.add("--merge-output-format");
            cmd.add("mp4");
        }
    }

    private void buildVideoCommand(List<String> cmd, DownloadConfig config) {
        cmd.add("-f");
        String fmt = config.getFormatString();
        if (config.isExplicitFormatPick()) {
            // User picked a specific format id — do not fall back to low-quality "best" (e.g. 18).
            cmd.add(fmt);
        } else {
            cmd.add(fmt + "/bestvideo+bestaudio/best");
        }
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
        // Never fall back to "best" — that muxes a full video file.
        cmd.add("bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio/bestaudio");
        cmd.add("-x");
        cmd.add("--audio-format");
        cmd.add("m4a");
        cmd.add("--embed-metadata");
        cmd.add("--embed-thumbnail");
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
        configureUtf8Process(pb);
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
