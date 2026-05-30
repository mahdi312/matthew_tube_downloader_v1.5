package com.mst.matt.matthew_tube_downloader.service.strategy;

import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.model.VideoInfo;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Strategy 2: pure-Java YouTube downloader via {@code sealedtx/java-youtube-downloader}.
 *
 * The library is loaded entirely through <b>reflection</b>, so:
 *   • module-info.java stays clean (no automatic-module-name pain),
 *   • the app keeps compiling and running even if the dep is missing,
 *   • we get a graceful runtime message instead of NoClassDefFoundError.
 *
 * Honors the existing SOCKS5 proxy field via {@code Config.proxy(host, port)}.
 *
 * Library: https://github.com/sealedtx/java-youtube-downloader
 */
public class PureJavaStrategy implements DownloadStrategy {

    private volatile Thread workerThread;

    @Override
    public StrategyType type() { return StrategyType.PURE_JAVA; }

    @Override
    public boolean isAvailable(DownloadConfig config, Consumer<String> logSink) {
        try {
            Class.forName("com.github.kiulian.downloader.YoutubeDownloader");
            return true;
        } catch (ClassNotFoundException e) {
            logSink.accept("ERROR: pure-Java library not on classpath.");
            logSink.accept("Add this dependency to pom.xml and rebuild:");
            logSink.accept("  <dependency>");
            logSink.accept("    <groupId>com.github.sealedtx</groupId>");
            logSink.accept("    <artifactId>java-youtube-downloader</artifactId>");
            logSink.accept("    <version>3.2.5</version>");
            logSink.accept("  </dependency>");
            logSink.accept("(JitPack repository already configured in pom.xml.)");
            return false;
        }
    }

    @Override
    public int downloadOne(DownloadConfig config,
                           VideoInfo video,
                           Consumer<String> logSink,
                           BiConsumer<Double, String> progressSink,
                           BooleanSupplier cancelCheck) throws Exception {

        workerThread = Thread.currentThread();
        String url = (video != null && video.getUrl() != null && !video.getUrl().isBlank())
                ? video.getUrl() : config.getUrl();
        String videoId = InvidiousStrategy.extractVideoId(url);
        if (videoId == null) {
            logSink.accept("ERROR: could not extract video ID from URL: " + url);
            return 1;
        }
        logSink.accept("[PureJava] video ID = " + videoId);

        try {
            // ── 1. Build YoutubeDownloader + Config ──
            Class<?> ytClass     = Class.forName("com.github.kiulian.downloader.YoutubeDownloader");
            Object   downloader  = ytClass.getDeclaredConstructor().newInstance();
            Object   ytConfig    = ytClass.getMethod("getConfig").invoke(downloader);

            // Apply SOCKS5 proxy if user set one.
            if (config.isUseProxy()
                    && config.getProxyHost() != null && !config.getProxyHost().isBlank()
                    && config.getProxyPort() != null && !config.getProxyPort().isBlank()) {
                try {
                    Method proxyMethod = ytConfig.getClass().getMethod("proxy", String.class, int.class);
                    proxyMethod.invoke(ytConfig, config.getProxyHost(),
                            Integer.parseInt(config.getProxyPort()));
                    logSink.accept("[PureJava] using proxy " + config.getProxyHost()
                            + ":" + config.getProxyPort());
                } catch (Exception px) {
                    logSink.accept("[PureJava] could not apply proxy: " + px.getMessage());
                }
            }

            // ── 2. RequestVideoInfo(videoId) → VideoInfo ──
            Class<?> reqVidInfoClass = Class.forName(
                    "com.github.kiulian.downloader.downloader.request.RequestVideoInfo");
            Object reqVid = reqVidInfoClass.getConstructor(String.class).newInstance(videoId);

            Method getVideoInfo = ytClass.getMethod("getVideoInfo", reqVidInfoClass);
            Object videoInfoResp = getVideoInfo.invoke(downloader, reqVid);
            Object videoInfo     = videoInfoResp.getClass().getMethod("data").invoke(videoInfoResp);
            if (videoInfo == null) throw new IllegalStateException("getVideoInfo returned null");

            Object details = videoInfo.getClass().getMethod("details").invoke(videoInfo);
            String title = (String) details.getClass().getMethod("title").invoke(details);
            logSink.accept("[PureJava] title: " + title);

            // ── 3. Pick format ──
            Object format;
            boolean audioOnly = config.getDownloadType() == DownloadConfig.DownloadType.AUDIO;
            if (audioOnly) {
                // bestAudioFormat()
                format = videoInfo.getClass().getMethod("bestAudioFormat").invoke(videoInfo);
            } else {
                // Prefer a muxed VideoWithAudio format at requested height; fall back to bestVideoWithAudioFormat.
                int target = config.getTargetHeight();
                Object muxList = videoInfo.getClass().getMethod("videoWithAudioFormats").invoke(videoInfo);
                format = pickByHeight(muxList, target);
                if (format == null) {
                    format = videoInfo.getClass().getMethod("bestVideoWithAudioFormat").invoke(videoInfo);
                }
                if (format == null) {
                    // No muxed → take best video-only (user can mux later with ffmpeg).
                    format = videoInfo.getClass().getMethod("bestVideoFormat").invoke(videoInfo);
                }
            }
            if (format == null) throw new IllegalStateException("no format found");

            String ext = (String) format.getClass().getMethod("extension")
                    .invoke(format).getClass().getMethod("value").invoke(
                            format.getClass().getMethod("extension").invoke(format));

            // ── 4. RequestVideoFileDownload(format).saveTo(dir).renameTo(name) ──
            Class<?> reqDlClass = Class.forName(
                    "com.github.kiulian.downloader.downloader.request.RequestVideoFileDownload");
            Class<?> formatBase = Class.forName(
                    "com.github.kiulian.downloader.model.videos.formats.Format");
            Object reqDl = reqDlClass.getConstructor(formatBase).newInstance(format);

            // Output dir + filename
            Path outDir = Paths.get(config.getOutputDir() != null ? config.getOutputDir() : ".");
            Files.createDirectories(outDir);
            String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            String filename;
            if (config.isPlaylist() && video != null) {
                filename = video.getIndex() + "-" + safeTitle;
            } else {
                filename = safeTitle;
            }

            reqDlClass.getMethod("saveTo", File.class).invoke(reqDl, outDir.toFile());
            reqDlClass.getMethod("renameTo", String.class).invoke(reqDl, filename);

            // Hook progress callback via YoutubeProgressCallback (interface).
            Class<?> cbClass = Class.forName(
                    "com.github.kiulian.downloader.downloader.YoutubeProgressCallback");
            Object progressCb = java.lang.reflect.Proxy.newProxyInstance(
                    cbClass.getClassLoader(),
                    new Class<?>[]{cbClass},
                    (proxy, method, args) -> {
                        if (cancelCheck.getAsBoolean()) {
                            throw new RuntimeException("cancelled by user");
                        }
                        switch (method.getName()) {
                            case "onDownloading" -> {
                                if (args != null && args.length > 0 && args[0] instanceof Integer pct) {
                                    progressSink.accept(pct / 100.0, pct + "%");
                                }
                            }
                            case "onFinished" -> {
                                progressSink.accept(1.0, "100%");
                                logSink.accept("[PureJava] finished.");
                            }
                            case "onError" -> {
                                if (args != null && args.length > 0 && args[0] instanceof Throwable t) {
                                    logSink.accept("[PureJava] error: " + t.getMessage());
                                }
                            }
                        }
                        return null;
                    });
            reqDlClass.getMethod("callback", cbClass).invoke(reqDl, progressCb);
            // Async true is set by the library when callback is supplied; force sync here.
            try { reqDlClass.getMethod("async").invoke(reqDl); } catch (NoSuchMethodException ignored) {}

            Method downloadVideoFile = ytClass.getMethod("downloadVideoFile", reqDlClass);
            Object respFile = downloadVideoFile.invoke(downloader, reqDl);
            // Block on Future-like response
            Object data = respFile.getClass().getMethod("data").invoke(respFile);
            if (data instanceof File f) {
                logSink.accept("[PureJava] saved → " + f.getAbsolutePath());
            } else {
                logSink.accept("[PureJava] saved (path returned: " + data + ")");
            }
            progressSink.accept(1.0, "100%");
            return 0;

        } catch (RuntimeException re) {
            if ("cancelled by user".equals(re.getMessage())) {
                logSink.accept("[PureJava] cancelled.");
                return -1;
            }
            throw re;
        } catch (Throwable t) {
            logSink.accept("[PureJava] failed: " + t.getClass().getSimpleName() + " — " + t.getMessage());
            throw new Exception(t);
        }
    }

    /** From a List<VideoWithAudioFormat>, pick the one with height closest to target without exceeding it. */
    private Object pickByHeight(Object list, int target) throws Exception {
        if (list == null) return null;
        @SuppressWarnings("unchecked")
        java.util.List<Object> formats = (java.util.List<Object>) list;
        Object best = null;
        int bestH = -1;
        for (Object f : formats) {
            Integer h = (Integer) f.getClass().getMethod("height").invoke(f);
            if (h == null || h <= 0) continue;
            if (target > 0 && h > target) continue;
            if (h > bestH) { bestH = h; best = f; }
        }
        return best;
    }

    @Override
    public void abort() {
        // The reflective callback above throws on cancelCheck.getAsBoolean() → triggers download abort.
        Thread t = workerThread;
        if (t != null) t.interrupt();
    }
}
