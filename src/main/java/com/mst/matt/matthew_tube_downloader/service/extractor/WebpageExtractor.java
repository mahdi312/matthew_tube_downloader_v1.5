package com.mst.matt.matthew_tube_downloader.service.extractor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feature 2 + 3:
 *   • Discovers downloadable video streams on ANY webpage (not just YouTube).
 *   • Returns a list of {@link FormatInfo} the user can pick from in the Quality
 *     Picker dialog.
 *
 * Two-stage strategy:
 *   1. Ask yt-dlp via {@code --dump-json -F} — handles 1000+ sites natively.
 *   2. If yt-dlp can't extract, fall back to raw HTML scraping:
 *        • <video src> / <source src>
 *        • og:video meta tags
 *        • .m3u8 / .mpd / .mp4 / .webm URLs referenced in <script> blocks
 */
public class WebpageExtractor {

    private static final Pattern VIDEO_SRC =
            Pattern.compile("<(?:video|source)[^>]*\\s+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_VIDEO =
            Pattern.compile("<meta[^>]*property=[\"']og:video(?::url)?[\"'][^>]*content=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDIA_URL =
            Pattern.compile("https?://[^\"'\\s<>\\\\]+\\.(?:m3u8|mpd|mp4|webm|m4a|mp3)(?:\\?[^\"'\\s<>\\\\]*)?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_TAG =
            Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15)).build();

    /* ───────────────────────── public entry points ───────────────────────── */

    /** Cheap heuristic: youtube.com / youtu.be → use the existing YouTube path. */
    public boolean looksLikeYouTube(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("youtube.com/") || u.contains("youtu.be/") || u.contains("youtube-nocookie.com/");
    }

    /** Page title — best effort, only used to label the Quality Picker. */
    public String pageTitle(String url) {
        try {
            String body = fetchHtml(url);
            if (body == null) return url;
            Matcher m = TITLE_TAG.matcher(body);
            if (m.find()) return m.group(1).trim();
        } catch (Exception ignored) {}
        return url;
    }

    /**
     * Discover formats. The result is non-null but may be empty.
     */
    public ExtractResult extract(String url, String proxyUrl, Consumer<String> log) {
        log.accept("[extract] " + url);
        // Step 1 — try yt-dlp.
        ExtractResult r = tryYtDlpDumpJson(url, proxyUrl, log);
        if (r != null && !r.formats.isEmpty()) {
            log.accept("[extract] yt-dlp found " + r.formats.size() + " formats");
            return r;
        }
        log.accept("[extract] yt-dlp did not return formats — falling back to HTML scraping.");
        // Step 2 — HTML scraping fallback.
        return tryHtmlScrape(url, log);
    }

    /* ───────────────────────── stage 1: yt-dlp ───────────────────────── */

    private ExtractResult tryYtDlpDumpJson(String url, String proxyUrl, Consumer<String> log) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("yt-dlp");
            if (proxyUrl != null && !proxyUrl.isBlank()) {
                cmd.add("--proxy"); cmd.add(proxyUrl);
            }
            cmd.add("--no-warnings");
            cmd.add("--dump-single-json");
            cmd.add(url);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) {
                log.accept("[extract] yt-dlp exit=" + p.exitValue() + ", trying scraper.");
                return null;
            }
            return parseYtDlpJson(sb.toString());
        } catch (Exception e) {
            log.accept("[extract] yt-dlp invocation failed: " + e.getMessage());
            return null;
        }
    }

    private ExtractResult parseYtDlpJson(String json) {
        if (json == null || json.isBlank()) return null;
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        ExtractResult res = new ExtractResult();
        res.url       = jsonString(o, "webpage_url", "");
        res.title     = jsonString(o, "title", res.url);
        res.extractor = jsonString(o, "extractor", "");
        res.engine    = "yt-dlp";

        if (!o.has("formats") || !o.get("formats").isJsonArray()) return res;
        JsonArray arr = o.getAsJsonArray("formats");
        for (JsonElement el : arr) {
            JsonObject f = el.getAsJsonObject();
            FormatInfo fi = new FormatInfo();
            fi.setFormatId  (jsonString(f, "format_id", ""));
            fi.setResolution(jsonString(f, "resolution", buildResolution(f)));
            fi.setFps       (jsonNumberStr(f, "fps"));
            fi.setCodec     (joinCodec(jsonString(f, "vcodec", ""), jsonString(f, "acodec", "")));
            fi.setContainer (jsonString(f, "ext", ""));
            fi.setBitrate   (jsonNumberStr(f, "tbr"));
            fi.setFilesize  (humanSize(numberOrNull(f, "filesize"), numberOrNull(f, "filesize_approx")));
            fi.setNote      (jsonString(f, "format_note", ""));
            fi.setDirectUrl (jsonString(f, "url", ""));
            fi.setKind      (deriveKind(jsonString(f, "vcodec", ""), jsonString(f, "acodec", "")));
            res.formats.add(fi);
        }
        return res;
    }

    /* ───────────────────────── stage 2: HTML scraping ───────────────────────── */

    private ExtractResult tryHtmlScrape(String url, Consumer<String> log) {
        ExtractResult res = new ExtractResult();
        res.url = url;
        res.engine = "html-scrape";
        try {
            String body = fetchHtml(url);
            if (body == null) { log.accept("[extract] could not fetch HTML."); return res; }
            Matcher tm = TITLE_TAG.matcher(body);
            res.title = tm.find() ? tm.group(1).trim() : url;

            Set<String> found = new LinkedHashSet<>();
            collect(VIDEO_SRC, body, found);
            collect(OG_VIDEO,  body, found);
            collect(MEDIA_URL, body, found);

            int idx = 1;
            for (String u : found) {
                FormatInfo fi = new FormatInfo();
                fi.setFormatId("scrape-" + (idx++));
                fi.setDirectUrl(absolutize(u, url));
                String ext = guessExt(u);
                fi.setContainer(ext);
                if ("m3u8".equalsIgnoreCase(ext)) {
                    fi.setKind("hls");
                    fi.setNote("HLS playlist — yt-dlp/ffmpeg required to download");
                } else if ("mpd".equalsIgnoreCase(ext)) {
                    fi.setKind("dash");
                    fi.setNote("MPEG-DASH — yt-dlp/ffmpeg required to download");
                } else if ("mp3".equalsIgnoreCase(ext) || "m4a".equalsIgnoreCase(ext)) {
                    fi.setKind("audio");
                } else {
                    fi.setKind("muxed");
                }
                fi.setResolution("?");
                fi.setNote(fi.getNote().isBlank() ? "Discovered via HTML scrape" : fi.getNote());
                res.formats.add(fi);
            }
            log.accept("[extract] HTML scrape found " + res.formats.size() + " candidate streams.");
        } catch (Exception e) {
            log.accept("[extract] scraping failed: " + e.getMessage());
        }
        return res;
    }

    private String fetchHtml(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) return null;
        return resp.body();
    }

    /* ───────────────────────── helpers ───────────────────────── */

    private static void collect(Pattern p, String body, Set<String> out) {
        Matcher m = p.matcher(body);
        while (m.find()) {
            String s = m.group(1);
            if (s != null && !s.isBlank()) out.add(s);
        }
    }

    private static String absolutize(String maybeRelative, String base) {
        try {
            if (maybeRelative.startsWith("//")) return "https:" + maybeRelative;
            if (maybeRelative.startsWith("http")) return maybeRelative;
            URI b = URI.create(base);
            return b.resolve(maybeRelative).toString();
        } catch (Exception e) {
            return maybeRelative;
        }
    }

    private static String guessExt(String url) {
        int dot = url.lastIndexOf('.');
        if (dot < 0) return "";
        String tail = url.substring(dot + 1).toLowerCase();
        int q = tail.indexOf('?');
        if (q >= 0) tail = tail.substring(0, q);
        return tail;
    }

    private static String jsonString(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsString(); } catch (Exception e) { return def; }
    }

    private static String jsonNumberStr(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try { return String.valueOf(o.get(key).getAsDouble()); } catch (Exception e) { return ""; }
    }

    private static Long numberOrNull(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        try { return o.get(key).getAsLong(); } catch (Exception e) { return null; }
    }

    private static String buildResolution(JsonObject f) {
        Long w = numberOrNull(f, "width");
        Long h = numberOrNull(f, "height");
        if (w != null && h != null) return w + "x" + h;
        if (h != null) return h + "p";
        return jsonString(f, "format_note", "");
    }

    private static String joinCodec(String vcodec, String acodec) {
        boolean noV = vcodec == null || vcodec.isBlank() || "none".equalsIgnoreCase(vcodec);
        boolean noA = acodec == null || acodec.isBlank() || "none".equalsIgnoreCase(acodec);
        if (noV && !noA) return "audio: " + acodec;
        if (!noV && noA) return "video: " + vcodec;
        if (!noV) return vcodec + " / " + acodec;
        return "";
    }

    private static String deriveKind(String vcodec, String acodec) {
        boolean noV = vcodec == null || vcodec.isBlank() || "none".equalsIgnoreCase(vcodec);
        boolean noA = acodec == null || acodec.isBlank() || "none".equalsIgnoreCase(acodec);
        if (noV && !noA) return "audio";
        if (!noV && noA) return "video";
        if (!noV) return "muxed";
        return "?";
    }

    private static String humanSize(Long exact, Long approx) {
        Long n = exact != null ? exact : approx;
        if (n == null || n <= 0) return "";
        double v = n;
        String[] units = {"B","KB","MB","GB","TB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        String prefix = (exact == null && approx != null) ? "~" : "";
        return String.format("%s%.2f %s", prefix, v, units[i]);
    }

    /* ───────────────────────── DTO ───────────────────────── */

    public static class ExtractResult {
        public String url;
        public String title;
        public String extractor;
        public String engine; // "yt-dlp" or "html-scrape"
        public final List<FormatInfo> formats = new ArrayList<>();
    }
}
