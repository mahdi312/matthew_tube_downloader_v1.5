package com.mst.matt.matthew_tube_downloader.service.dependency;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Detects and (with user confirmation) installs/updates the external tools the
 * app depends on.
 *
 * Tools managed:
 *   • yt-dlp       — primary downloader; checked against GitHub releases for latest.
 *   • yt-dlp-proxy — Strategy 3 wrapper.
 *   • ffmpeg       — required for merging video+audio.
 *   • python/pip   — used to install/update the Python-based tools.
 *
 * No action is ever executed without an explicit confirmation in the UI layer.
 */
public class DependencyManager {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** Build the list of tools in the order the UI should show them. */
    public List<ToolStatus> tools() {
        List<ToolStatus> list = new ArrayList<>();
        list.add(new ToolStatus("yt-dlp",       "yt-dlp"));
        list.add(new ToolStatus("yt-dlp-proxy", "yt-dlp-proxy"));
        list.add(new ToolStatus("ffmpeg",       "ffmpeg"));
        list.add(new ToolStatus("python",       "python3"));
        list.add(new ToolStatus("pip",          "pip3"));
        return list;
    }

    /** Refresh one tool's state. Network call only for yt-dlp (to compare with latest). */
    public void refresh(ToolStatus tool, Consumer<String> log) {
        String name = tool.getName();
        String cmd  = tool.getCommand();
        String v = readVersion(cmd, versionFlagsFor(name));
        if (v == null) {
            v = readVersion(altCommandFor(cmd), versionFlagsFor(name));
            if (v != null) log.accept("[deps] found " + name + " via fallback command");
        }
        if (v == null) {
            tool.apply(ToolStatus.State.MISSING, "not installed", "");
            return;
        }

        if ("yt-dlp".equals(name)) {
            String latest = fetchLatestYtDlpVersion();
            if (latest != null && !latest.equals(v)) {
                tool.apply(ToolStatus.State.OUTDATED, v, latest);
                log.accept("[deps] yt-dlp installed=" + v + ", latest=" + latest);
            } else {
                tool.apply(ToolStatus.State.OK, v, latest == null ? "" : latest);
            }
        } else {
            tool.apply(ToolStatus.State.OK, v, "");
        }
    }

    /** Refresh all in sequence (callers should invoke from a background thread). */
    public void refreshAll(List<ToolStatus> tools, Consumer<String> log) {
        for (ToolStatus t : tools) refresh(t, log);
    }

    /** Quick "is yt-dlp updated?" probe for the startup notification. */
    public ToolStatus.State quickCheckYtDlp() {
        String v = readVersion("yt-dlp", new String[]{"--version"});
        if (v == null) return ToolStatus.State.MISSING;
        String latest = fetchLatestYtDlpVersion();
        if (latest == null) return ToolStatus.State.UNKNOWN;
        return v.equals(latest) ? ToolStatus.State.OK : ToolStatus.State.OUTDATED;
    }

    /* ───────────────────────── install / update ───────────────────────── */

    /**
     * Run install/update for the given tool. Caller is responsible for confirming
     * the operation with the user first.
     */
    public int install(ToolStatus tool, Consumer<String> log) throws Exception {
        String os = osName();
        String name = tool.getName();
        log.accept("[deps] " + tool.getActionLabel() + " → " + name + "  (OS=" + os + ")");

        return switch (name) {
            case "yt-dlp"       -> installYtDlp(log);
            case "yt-dlp-proxy" -> runPipInstall("yt-dlp-proxy", log);
            case "ffmpeg"       -> installFfmpeg(log);
            case "python"       -> { log.accept("Please install Python 3 manually from https://python.org"); yield 1; }
            case "pip"          -> runProcess(new String[]{"python3", "-m", "ensurepip", "--upgrade"}, log);
            default             -> { log.accept("Unknown tool: " + name); yield 1; }
        };
    }

    private int installYtDlp(Consumer<String> log) throws Exception {
        // 1) If yt-dlp is on PATH, prefer its self-update mechanism.
        String v = readVersion("yt-dlp", new String[]{"--version"});
        if (v != null) {
            log.accept("[deps] trying:  yt-dlp -U");
            int code = runProcess(new String[]{"yt-dlp", "-U"}, log);
            if (code == 0) return 0;
            log.accept("[deps] self-update failed (exit=" + code + "), falling back to pip upgrade");
        }
        // 2) pip install -U
        return runPipInstall("yt-dlp", log, /*upgrade*/ true);
    }

    private int installFfmpeg(Consumer<String> log) throws Exception {
        String os = osName();
        if (os.contains("win")) {
            log.accept("[deps] running: winget install --id Gyan.FFmpeg");
            int code = runProcess(new String[]{"winget", "install", "--id", "Gyan.FFmpeg",
                    "-e", "--accept-package-agreements", "--accept-source-agreements"}, log);
            if (code == 0) return 0;
            log.accept("[deps] winget failed — try chocolatey:  choco install ffmpeg");
            return code;
        } else if (os.contains("mac")) {
            log.accept("[deps] running: brew install ffmpeg");
            return runProcess(new String[]{"brew", "install", "ffmpeg"}, log);
        } else {
            // Linux: try apt (most common). Fall back to instructions.
            log.accept("[deps] running: sudo apt-get install -y ffmpeg");
            int code = runProcess(new String[]{"sudo", "apt-get", "install", "-y", "ffmpeg"}, log);
            if (code != 0) {
                log.accept("[deps] apt failed — try: sudo dnf install ffmpeg  OR  sudo pacman -S ffmpeg");
            }
            return code;
        }
    }

    private int runPipInstall(String pkg, Consumer<String> log) throws Exception {
        return runPipInstall(pkg, log, /*upgrade*/ false);
    }

    private int runPipInstall(String pkg, Consumer<String> log, boolean upgrade) throws Exception {
        String[] pipCandidates = {"pip3", "pip", "python3 -m pip", "py -m pip"};
        for (String p : pipCandidates) {
            List<String> cmd = new ArrayList<>();
            for (String t : p.split(" ")) cmd.add(t);
            cmd.add("install");
            if (upgrade) cmd.add("-U");
            cmd.add("--user");
            cmd.add(pkg);
            log.accept("[deps] trying: " + String.join(" ", cmd));
            int code = runProcess(cmd.toArray(new String[0]), log);
            if (code == 0) return 0;
        }
        log.accept("[deps] all pip variants failed.");
        return 1;
    }

    /* ───────────────────────── helpers ───────────────────────── */

    private String[] versionFlagsFor(String name) {
        return switch (name) {
            case "ffmpeg" -> new String[]{"-version"};   // ffmpeg uses single-dash
            default       -> new String[]{"--version"};
        };
    }

    private String altCommandFor(String cmd) {
        return switch (cmd) {
            case "python3" -> "python";
            case "pip3"    -> "pip";
            default        -> cmd;
        };
    }

    private String readVersion(String cmd, String[] flags) {
        try {
            List<String> args = new ArrayList<>();
            args.add(cmd);
            for (String f : flags) args.add(f);
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes()).trim();
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            // Most tools print only the version on first line of stdout.
            String first = out.lines().findFirst().orElse("").trim();
            // ffmpeg prints "ffmpeg version 6.1 …" — extract the second token.
            if (first.startsWith("ffmpeg version")) {
                String[] parts = first.split("\\s+");
                if (parts.length >= 3) return parts[2];
            }
            return first.isBlank() ? out.trim() : first;
        } catch (Exception e) {
            return null;
        }
    }

    /** Latest yt-dlp release tag from GitHub. */
    private String fetchLatestYtDlpVersion() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MatthewTubeDownloader/1.3")
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) return null;
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            return root.has("tag_name") ? root.get("tag_name").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int runProcess(String[] cmd, Consumer<String> log) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) log.accept(line);
        }
        return p.waitFor();
    }

    private static String osName() { return System.getProperty("os.name", "").toLowerCase(); }

    /** Used by Tools tab to display per-OS install hints. */
    public Map<String, String> installHints() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("yt-dlp",       "pip install -U --user yt-dlp   (or: yt-dlp -U)");
        m.put("yt-dlp-proxy", "pip install --user yt-dlp-proxy   then:  yt-dlp-proxy update");
        m.put("ffmpeg",       osName().contains("win") ? "winget install Gyan.FFmpeg"
                : osName().contains("mac") ? "brew install ffmpeg"
                : "sudo apt-get install ffmpeg");
        m.put("python",       "https://python.org/downloads");
        m.put("pip",          "python3 -m ensurepip --upgrade");
        return m;
    }
}
