package com.mst.matt.matthew_tube_downloader.service.dependency;

import com.mst.matt.matthew_tube_downloader.service.settings.AppSettings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Optional YouTube PO Token via {@code bgutil-ytdlp-pot-provider} HTTP server.
 *
 * @see <a href="https://github.com/Brainicism/bgutil-ytdlp-pot-provider">bgutil-ytdlp-pot-provider</a>
 */
public final class PotProviderHelper {

    public static final String PIP_PACKAGE = "bgutil-ytdlp-pot-provider";
    public static final String GIT_REPO =
            "https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git";
    public static final int DEFAULT_HTTP_PORT = 4416;
    public static final String DEFAULT_HTTP_BASE = "http://127.0.0.1:4416";

    /**
     * Works with cookies for 360p–1080p without PO tokens (verified on YouTube).
     * Do NOT use {@code tv_embedded} alone — it often returns storyboards only.
     */
    private static final String CLIENTS_WITHOUT_POT = "android,tv,web";
    /** Extra clients when the bgutil HTTP server is running. */
    private static final String CLIENTS_WITH_POT = "android,tv,web,tv_embedded,web_creator";

    private PotProviderHelper() {}

    public static Path defaultScriptHome() {
        return Path.of(System.getProperty("user.home"), "bgutil-ytdlp-pot-provider");
    }

    public static Path scriptServerHome() {
        return defaultScriptHome().resolve("server");
    }

    public static boolean isHttpServerReachable(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = DEFAULT_HTTP_BASE;
        try {
            URI uri = URI.create(baseUrl.trim());
            String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
            int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_HTTP_PORT;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 500);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isScriptProviderPresent() {
        return Files.isDirectory(scriptServerHome());
    }

    public static boolean isPotActive(AppSettings settings) {
        if (settings == null || !settings.usePotProvider) return false;
        String url = settings.potProviderHttpUrl;
        if (url == null || url.isBlank()) url = DEFAULT_HTTP_BASE;
        return isHttpServerReachable(url);
    }

    public static boolean isReady(String httpBaseUrl) {
        return isHttpServerReachable(httpBaseUrl);
    }

    /** Official Deno HTTP server command (see bgutil README). */
    public static String httpServerStartCommand() {
        Path nm = scriptServerHome().resolve("node_modules");
        return "cd \"" + nm + "\" && deno run --allow-env --allow-net --allow-ffi=. --allow-read=. ../src/main.ts";
    }

    /** Node alternative after {@code npm install} + build in server folder. */
    public static String httpServerStartCommandNode() {
        return "cd \"" + scriptServerHome() + "\" && node build/main.js";
    }

    public static void appendYoutubeExtractorArgs(List<String> cmd, AppSettings settings) {
        if (cmd == null) return;
        cmd.add("--extractor-args");
        if (isPotActive(settings)) {
            cmd.add("youtube:player_client=" + CLIENTS_WITH_POT + ";youtubetab:skip=authcheck");
            String base = settings.potProviderHttpUrl;
            if (base == null || base.isBlank()) base = DEFAULT_HTTP_BASE;
            cmd.add("--extractor-args");
            cmd.add("youtubepot-bgutilhttp:base_url=" + base.trim());
        } else {
            cmd.add("youtube:player_client=" + CLIENTS_WITHOUT_POT + ";youtubetab:skip=authcheck");
        }
    }

    /** Install npm deps in {@code server/} (required before starting HTTP server). */
    public static int installServerDependencies(Consumer<String> log) {
        Path server = scriptServerHome();
        if (!Files.isDirectory(server)) {
            log.accept("[deps] server folder missing: " + server);
            return 1;
        }
        log.accept("[deps] running: npm install  (in " + server + ")");
        try {
            ProcessBuilder pb = new ProcessBuilder("npm", "install");
            pb.directory(server.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) log.accept(line);
            }
            int code = p.waitFor();
            if (code == 0) {
                log.accept("[deps] npm install OK");
                log.accept("[deps] Start HTTP server:");
                log.accept("[deps]   " + httpServerStartCommand());
            } else {
                log.accept("[deps] npm install exited with " + code);
            }
            return code;
        } catch (Exception e) {
            log.accept("[deps] npm install failed: " + e.getMessage());
            return 1;
        }
    }

    @Deprecated
    public static void appendExtractorArgs(List<String> cmd, boolean enabled, String httpBaseUrl) {
        AppSettings s = new AppSettings();
        s.usePotProvider = enabled;
        s.potProviderHttpUrl = httpBaseUrl;
        appendYoutubeExtractorArgs(cmd, s);
    }

    @Deprecated
    public static void appendExtractorArgsForMetadata(List<String> cmd, boolean enabled, String httpBaseUrl) {
        appendExtractorArgs(cmd, enabled, httpBaseUrl);
    }

    /** @deprecated Use {@link #installServerDependencies(Consumer)} */
    @Deprecated
    public static int installScriptDependencies(Consumer<String> log) {
        return installServerDependencies(log);
    }
}
