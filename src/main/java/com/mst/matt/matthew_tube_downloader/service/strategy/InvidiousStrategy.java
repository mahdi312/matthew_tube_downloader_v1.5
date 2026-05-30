package com.mst.matt.matthew_tube_downloader.service.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.model.VideoInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strategy 1: Invidious / Piped API.
 *
 * Flow:
 *   1. Extract YouTube video ID from {@code config.getUrl()}.
 *   2. Hit {@code GET <instance>/api/v1/videos/<id>} — JSON contains formatStreams[] +
 *      adaptiveFormats[], each with a direct googlevideo.com {@code url}.
 *   3. Choose the best stream matching the user's quality preference.
 *   4. Download the stream directly with {@link HttpClient} into the output dir.
 *
 * Key trick: the resolved {@code googlevideo.com} URLs are typically reachable from
 * Iran even when youtube.com is SNI-filtered, because ISPs only block the SNI for
 * youtube.com itself.
 *
 * If the chosen instance fails and {@code config.isInvidiousAutoRotate()} is true,
 * the strategy fetches the live instance list from api.invidious.io and retries.
 */
public class InvidiousStrategy implements DownloadStrategy {

    /** Default Invidious instances — used as fallback when the auto-rotate list cannot be fetched. */
    private static final List<String> FALLBACK_INSTANCES = List.of(
            "https://yewtu.be",
            "https://invidious.nerdvpn.de",
            "https://invidious.privacydev.net",
            "https://inv.nadeko.net",
            "https://invidious.protokolla.fi",
            "https://invidious.f5.si"
    );

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?v=|embed/|shorts/|v/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private final HttpClient client;
    private volatile boolean cancelled;

    public InvidiousStrategy() {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public StrategyType type() { return StrategyType.INVIDIOUS; }

    /* ───────────────────────── public API ───────────────────────── */

    @Override
    public int downloadOne(DownloadConfig config,
                           VideoInfo video,
                           Consumer<String> logSink,
                           BiConsumer<Double, String> progressSink,
                           BooleanSupplier cancelCheck) throws Exception {

        cancelled = false;
        String url = (video != null && video.getUrl() != null && !video.getUrl().isBlank())
                ? video.getUrl() : config.getUrl();

        String videoId = extractVideoId(url);
        if (videoId == null) {
            logSink.accept("ERROR: could not extract video ID from URL: " + url);
            return 1;
        }
        logSink.accept("[Invidious] video ID = " + videoId);

        // Build candidate instance list (user-provided first, then rotation pool).
        List<String> candidates = buildCandidateInstances(config, logSink);

        JsonObject videoJson = null;
        String usedInstance = null;
        for (String instance : candidates) {
            if (cancelCheck.getAsBoolean()) return -1;
            try {
                logSink.accept("[Invidious] trying instance: " + instance);
                videoJson = fetchVideoJson(instance, videoId, config);
                usedInstance = instance;
                logSink.accept("[Invidious] ✓ resolved via " + instance);
                break;
            } catch (Exception e) {
                logSink.accept("[Invidious] instance failed (" + instance + "): " + e.getMessage());
            }
        }
        if (videoJson == null) {
            logSink.accept("ERROR: all Invidious instances failed.");
            return 2;
        }

        // Pick best stream for selected quality + type.
        StreamPick pick = pickStream(videoJson, config);
        if (pick == null) {
            logSink.accept("ERROR: no matching stream found in Invidious response.");
            return 3;
        }
        logSink.accept("[Invidious] picked: " + pick.label + "  (" + pick.container + ")");

        // Determine output file path.
        String title = jsonString(videoJson, "title", "video_" + videoId);
        String safeTitle = sanitizeFilename(title);
        String ext = pick.container != null && !pick.container.isBlank() ? pick.container : "mp4";
        Path outDir = Paths.get(config.getOutputDir() != null ? config.getOutputDir() : ".");
        Files.createDirectories(outDir);
        Path outFile;
        if (config.isPlaylist() && video != null) {
            outFile = outDir.resolve(String.format("%d-%s.%s", video.getIndex(), safeTitle, ext));
        } else {
            outFile = outDir.resolve(safeTitle + "." + ext);
        }
        logSink.accept("[Invidious] saving to: " + outFile);

        // Stream-download with progress.
        return streamDownload(pick.url, outFile, config, logSink, progressSink, cancelCheck);
    }

    @Override
    public void abort() { cancelled = true; }

    /* ───────────────────────── helpers ───────────────────────── */

    private List<String> buildCandidateInstances(DownloadConfig config, Consumer<String> log) {
        Set<String> seen = new LinkedHashSet<>();
        String userInstance = config.getInvidiousInstance();
        if (userInstance != null && !userInstance.isBlank()) {
            seen.add(userInstance.trim().replaceAll("/+$", ""));
        }
        if (config.isInvidiousAutoRotate()) {
            try {
                seen.addAll(fetchLiveInstances());
                log.accept("[Invidious] fetched live instance list from api.invidious.io");
            } catch (Exception e) {
                log.accept("[Invidious] could not fetch live instance list: " + e.getMessage()
                        + " — using fallback list.");
            }
            seen.addAll(FALLBACK_INSTANCES);
        }
        if (seen.isEmpty()) seen.addAll(FALLBACK_INSTANCES);
        return new ArrayList<>(seen);
    }

    /** Fetch live list from https://api.invidious.io/instances.json (HTTPS-only, type=https). */
    private List<String> fetchLiveInstances() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.invidious.io/instances.json?pretty=1&sort_by=health"))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "MatthewTubeDownloader/1.2")
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode());

        JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
        List<String> instances = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonArray pair = el.getAsJsonArray();
            // pair[0] = hostname, pair[1] = { type, uri, api, ... }
            JsonObject obj = pair.get(1).getAsJsonObject();
            String type = jsonString(obj, "type", "");
            boolean api = obj.has("api") && !obj.get("api").isJsonNull() && obj.get("api").getAsBoolean();
            String uri = jsonString(obj, "uri", "");
            if ("https".equalsIgnoreCase(type) && api && !uri.isBlank()) {
                instances.add(uri.replaceAll("/+$", ""));
            }
        }
        return instances;
    }

    /** GET /api/v1/videos/:id on a given instance. */
    private JsonObject fetchVideoJson(String instance, String videoId, DownloadConfig config)
            throws IOException, InterruptedException {
        URI u = URI.create(instance + "/api/v1/videos/" + videoId);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(u)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "MatthewTubeDownloader/1.2")
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode());
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    /** Choose the best stream for the requested type + quality. */
    private StreamPick pickStream(JsonObject videoJson, DownloadConfig config) {
        boolean audioOnly = config.getDownloadType() == DownloadConfig.DownloadType.AUDIO;

        // Prefer formatStreams (single-file muxed) when audio+video, then adaptive.
        if (!audioOnly && videoJson.has("formatStreams")) {
            StreamPick muxed = pickFromArray(videoJson.getAsJsonArray("formatStreams"),
                    config.getTargetHeight(), false);
            if (muxed != null) return muxed;
        }
        if (videoJson.has("adaptiveFormats")) {
            return pickFromArray(videoJson.getAsJsonArray("adaptiveFormats"),
                    config.getTargetHeight(), audioOnly);
        }
        return null;
    }

    private StreamPick pickFromArray(JsonArray arr, int targetHeight, boolean audioOnly) {
        StreamPick best = null;
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            String url = jsonString(o, "url", "");
            if (url.isBlank()) continue;
            String type = jsonString(o, "type", "");
            String container = jsonString(o, "container", "");
            String quality = jsonString(o, "qualityLabel", jsonString(o, "quality", ""));
            int h = 0;
            if (o.has("resolution") && !o.get("resolution").isJsonNull()) {
                Matcher m = Pattern.compile("(\\d+)p").matcher(o.get("resolution").getAsString());
                if (m.find()) h = Integer.parseInt(m.group(1));
            }
            boolean isAudio = type.startsWith("audio/") || quality.toLowerCase().contains("audio");

            if (audioOnly && !isAudio) continue;
            if (!audioOnly && isAudio) continue;

            // Filter by target height for video; pick the highest <= target (or highest if target=0).
            if (!audioOnly && targetHeight > 0 && h > targetHeight) continue;

            int score = audioOnly ? 1 : Math.max(h, 1);
            if (best == null || score > best.score) {
                best = new StreamPick(url, quality, container.isBlank() ? "mp4" : container, score);
            }
        }
        return best;
    }

    /** Stream a URL into a file with periodic progress callbacks. */
    private int streamDownload(String url, Path out, DownloadConfig config,
                               Consumer<String> log, BiConsumer<Double, String> progress,
                               BooleanSupplier cancelCheck) throws IOException {

        URLConnection conn = openConnection(url, config);
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

        long total = conn.getContentLengthLong();
        if (total > 0) log.accept(String.format("[Invidious] size: %.2f MB", total / 1_048_576.0));

        Files.createDirectories(out.getParent());
        try (var in = conn.getInputStream();
             OutputStream fos = Files.newOutputStream(out,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] buf = new byte[64 * 1024];
            long downloaded = 0;
            long lastReport = System.currentTimeMillis();
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled || cancelCheck.getAsBoolean()) {
                    log.accept("[Invidious] cancelled.");
                    return -1;
                }
                fos.write(buf, 0, n);
                downloaded += n;
                long now = System.currentTimeMillis();
                if (now - lastReport >= 250) {
                    if (total > 0) {
                        double frac = (double) downloaded / total;
                        progress.accept(frac, String.format("%.1f%%  (%.1f / %.1f MB)",
                                frac * 100, downloaded / 1_048_576.0, total / 1_048_576.0));
                    } else {
                        progress.accept(-1.0, String.format("%.1f MB", downloaded / 1_048_576.0));
                    }
                    lastReport = now;
                }
            }
            progress.accept(1.0, "100.0%");
            log.accept(String.format("[Invidious] saved %.2f MB → %s", downloaded / 1_048_576.0, out));
        }
        return 0;
    }

    private URLConnection openConnection(String url, DownloadConfig config) throws IOException {
        URL u = new URL(url);
        if (config.isUseProxy() && config.getProxyUrl() != null) {
            // Respect the user's existing SOCKS5 proxy field.
            try {
                Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                        new InetSocketAddress(config.getProxyHost(), Integer.parseInt(config.getProxyPort())));
                return u.openConnection(proxy);
            } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return u.openConnection();
    }

    /* ───────────────────────── tiny utils ───────────────────────── */

    public static String extractVideoId(String url) {
        if (url == null) return null;
        Matcher m = VIDEO_ID_PATTERN.matcher(url);
        if (m.find()) return m.group(1);
        // Fallback: ?v=XXXX as query param
        try {
            URI u = URI.create(url);
            String q = u.getQuery();
            if (q != null) for (String part : q.split("&")) {
                if (part.startsWith("v=")) {
                    String v = URLDecoder.decode(part.substring(2), StandardCharsets.UTF_8);
                    if (v.length() >= 11) return v.substring(0, 11);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String jsonString(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsString(); } catch (Exception e) { return def; }
    }

    private static String sanitizeFilename(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private record StreamPick(String url, String label, String container, int score) {}

    /** Make spotbugs happy about unused field name "cancelled" in record context. */
    @SuppressWarnings("unused")
    private static List<String> dedupe(List<String> xs) { return new ArrayList<>(new LinkedHashSet<>(xs)); }
    @SuppressWarnings("unused")
    private static String[] split(String s) { return s == null ? new String[0] : s.split(","); }
    @SuppressWarnings("unused")
    private static List<String> asList(String[] xs) { return Arrays.asList(xs); }
}
