package com.mst.matt.matthew_tube_downloader.service.dependency;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.mst.matt.matthew_tube_downloader.service.settings.AppSettings;
import com.mst.matt.matthew_tube_downloader.service.settings.SettingsManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and (with user confirmation) installs/updates external tools.
 *
 * Tools: yt-dlp, yt-dlp-proxy, ffmpeg, deno, node, bgutil-pot, python, pip.
 */
public class DependencyManager {

    private static final Pattern VERSION_TOKEN = Pattern.compile("(\\d+(?:\\.\\d+)*)");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public List<ToolStatus> tools() {
        List<ToolStatus> list = new ArrayList<>();
        list.add(new ToolStatus("yt-dlp",       "yt-dlp"));
        list.add(new ToolStatus("yt-dlp-proxy", "yt-dlp-proxy"));
        list.add(new ToolStatus("ffmpeg",       "ffmpeg"));
        list.add(new ToolStatus("deno",         "deno"));
        list.add(new ToolStatus("node",         "node"));
        list.add(new ToolStatus("bgutil-pot",   PotProviderHelper.PIP_PACKAGE));
        boolean win = osName().contains("win");
        list.add(new ToolStatus("python",       win ? "python" : "python3"));
        list.add(new ToolStatus("pip",          win ? "pip" : "pip3"));
        return list;
    }

    public void refresh(ToolStatus tool, Consumer<String> log) {
        String name = tool.getName();
        if ("bgutil-pot".equals(name)) {
            refreshBgutilPot(tool, log);
            return;
        }

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
            if (latest != null && isYtDlpOutdated(v, latest)) {
                tool.apply(ToolStatus.State.OUTDATED, v, latest);
                log.accept("[deps] yt-dlp installed=" + v + ", latest=" + latest);
            } else {
                tool.apply(ToolStatus.State.OK, v, latest == null ? "" : latest);
            }
        } else {
            tool.apply(ToolStatus.State.OK, v, "");
        }
    }

    private void refreshBgutilPot(ToolStatus tool, Consumer<String> log) {
        String v = readPipPackageVersion(PotProviderHelper.PIP_PACKAGE);
        if (v == null) {
            tool.apply(ToolStatus.State.MISSING, "not installed", "");
            return;
        }
        AppSettings settings = SettingsManager.load();
        if (PotProviderHelper.isPotActive(settings)) {
            tool.apply(ToolStatus.State.OK, v, "HTTP server OK");
            log.accept("[deps] bgutil POT plugin=" + v + ", HTTP server reachable");
        } else if (settings.usePotProvider) {
            tool.apply(ToolStatus.State.UNKNOWN, v, "");
            tool.setActionLabel("Setup");
            tool.setStatusLabel("⚠ Enabled in Settings but HTTP server down");
            log.accept("[deps] PO enabled in Settings but HTTP server not reachable at "
                    + settings.potProviderHttpUrl);
        } else {
            tool.apply(ToolStatus.State.OK, v, "disabled in Settings");
            tool.setActionLabel("");
            tool.setStatusLabel("✓ Installed (off in Settings)");
            log.accept("[deps] bgutil plugin=" + v + " (PO token disabled in Settings)");
        }
    }

    public void refreshAll(List<ToolStatus> tools, Consumer<String> log) {
        for (ToolStatus t : tools) refresh(t, log);
    }

    public ToolStatus.State quickCheckYtDlp() {
        String v = readVersion("yt-dlp", new String[]{"--version"});
        if (v == null) return ToolStatus.State.MISSING;
        String latest = fetchLatestYtDlpVersion();
        if (latest == null) return ToolStatus.State.UNKNOWN;
        return isYtDlpOutdated(v, latest) ? ToolStatus.State.OUTDATED : ToolStatus.State.OK;
    }

    public int install(ToolStatus tool, Consumer<String> log) throws Exception {
        String os = osName();
        String name = tool.getName();
        log.accept("[deps] " + tool.getActionLabel() + " → " + name + "  (OS=" + os + ")");

        return switch (name) {
            case "yt-dlp"       -> installYtDlp(log);
            case "yt-dlp-proxy" -> runPipInstall("yt-dlp-proxy", log);
            case "ffmpeg"       -> installFfmpeg(log);
            case "deno"         -> installDeno(log);
            case "node"         -> installNode(log);
            case "bgutil-pot"   -> installBgutilPot(log);
            case "python"       -> { log.accept("Install Python from https://python.org/downloads"); yield 1; }
            case "pip"          -> runProcess(new String[]{altCommandFor("python3"), "-m", "ensurepip", "--upgrade"}, log);
            default             -> { log.accept("Unknown tool: " + name); yield 1; }
        };
    }

    private int installYtDlp(Consumer<String> log) throws Exception {
        String v = readVersion("yt-dlp", new String[]{"--version"});
        if (v != null) {
            log.accept("[deps] trying:  yt-dlp -U");
            int code = runProcess(new String[]{"yt-dlp", "-U"}, log);
            if (code == 0) return 0;
            log.accept("[deps] self-update failed (exit=" + code + "), falling back to pip upgrade");
        }
        return runPipInstall("yt-dlp", log, true);
    }

    private int installBgutilPot(Consumer<String> log) throws Exception {
        int pip = runPipInstall(PotProviderHelper.PIP_PACKAGE, log, true);
        if (pip != 0) return pip;

        if (PotProviderHelper.isScriptProviderPresent()) {
            log.accept("[deps] server folder at " + PotProviderHelper.defaultScriptHome());
            return PotProviderHelper.installServerDependencies(log);
        }

        Path home = PotProviderHelper.defaultScriptHome();
        if (!Files.exists(home)) {
            log.accept("[deps] cloning POT script provider → " + home);
            int git = runProcess(new String[]{
                    "git", "clone", "--depth", "1", PotProviderHelper.GIT_REPO, home.toString()
            }, log);
            if (git != 0) {
                log.accept("[deps] git clone failed — install git or clone manually:");
                log.accept("[deps]   git clone " + PotProviderHelper.GIT_REPO + " " + home);
                return git;
            }
        } else if (!PotProviderHelper.isScriptProviderPresent()) {
            log.accept("[deps] folder exists but server/ missing — re-clone or pull manually");
        }

        int deps = PotProviderHelper.installServerDependencies(log);
        if (deps != 0) {
            log.accept("[deps] run manually: cd server && npm install");
        }

        log.accept("[deps] bgutil plugin installed. Start HTTP server, then enable PO in Settings.");
        log.accept("[deps]   " + PotProviderHelper.httpServerStartCommand());
        return deps;
    }

    private int installNode(Consumer<String> log) throws Exception {
        String os = osName();
        if (os.contains("win")) {
            log.accept("[deps] running: winget install OpenJS.NodeJS.LTS");
            int code = runProcess(new String[]{"winget", "install", "--id", "OpenJS.NodeJS.LTS",
                    "-e", "--accept-package-agreements", "--accept-source-agreements"}, log);
            if (code == 0) return 0;
        } else if (os.contains("mac")) {
            return runProcess(new String[]{"brew", "install", "node"}, log);
        }
        log.accept("[deps] install Node.js: https://nodejs.org/");
        return 1;
    }

    private int installDeno(Consumer<String> log) throws Exception {
        String os = osName();
        if (os.contains("win")) {
            log.accept("[deps] running: winget install DenoLand.Deno");
            int code = runProcess(new String[]{"winget", "install", "--id", "DenoLand.Deno",
                    "-e", "--accept-package-agreements", "--accept-source-agreements"}, log);
            if (code == 0) return 0;
        } else if (os.contains("mac")) {
            return runProcess(new String[]{"brew", "install", "deno"}, log);
        }
        log.accept("[deps] install Deno: https://deno.land/#installation");
        return 1;
    }

    private int installFfmpeg(Consumer<String> log) throws Exception {
        String os = osName();
        if (os.contains("win")) {
            log.accept("[deps] running: winget install Gyan.FFmpeg");
            int code = runProcess(new String[]{"winget", "install", "--id", "Gyan.FFmpeg",
                    "-e", "--accept-package-agreements", "--accept-source-agreements"}, log);
            if (code == 0) return 0;
            log.accept("[deps] winget failed — try: choco install ffmpeg");
            return code;
        } else if (os.contains("mac")) {
            return runProcess(new String[]{"brew", "install", "ffmpeg"}, log);
        } else {
            log.accept("[deps] running: sudo apt-get install -y ffmpeg");
            int code = runProcess(new String[]{"sudo", "apt-get", "install", "-y", "ffmpeg"}, log);
            if (code != 0) {
                log.accept("[deps] apt failed — try: sudo dnf install ffmpeg");
            }
            return code;
        }
    }

    private int runPipInstall(String pkg, Consumer<String> log) throws Exception {
        return runPipInstall(pkg, log, false);
    }

    private int runPipInstall(String pkg, Consumer<String> log, boolean upgrade) throws Exception {
        String[] pipCandidates = osName().contains("win")
                ? new String[]{"pip", "pip3", "python -m pip", "py -m pip"}
                : new String[]{"pip3", "pip", "python3 -m pip"};
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

    private String[] versionFlagsFor(String name) {
        return switch (name) {
            case "ffmpeg" -> new String[]{"-version"};
            default       -> new String[]{"--version"};
        };
    }

    private String altCommandFor(String cmd) {
        return switch (cmd) {
            case "python3" -> "python";
            case "python"  -> "python3";
            case "pip3"    -> "pip";
            case "pip"     -> "pip3";
            default        -> cmd;
        };
    }

    private String readPipPackageVersion(String packageName) {
        String[] pipCandidates = osName().contains("win")
                ? new String[]{"pip", "pip3", "python -m pip", "py -m pip"}
                : new String[]{"pip3", "pip", "python3 -m pip"};
        for (String p : pipCandidates) {
            try {
                List<String> cmd = new ArrayList<>();
                for (String t : p.split(" ")) cmd.add(t);
                cmd.add("show");
                cmd.add(packageName);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String out;
                try (var in = proc.getInputStream()) {
                    out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (!proc.waitFor(15, TimeUnit.SECONDS) || proc.exitValue() != 0) continue;
                for (String line : out.split("\\R")) {
                    if (line.regionMatches(true, 0, "Version:", 0, 8)) {
                        String ver = line.substring(8).trim();
                        Matcher m = VERSION_TOKEN.matcher(ver);
                        return m.find() ? m.group(1) : ver;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
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
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            String first = out.lines().findFirst().orElse("").trim();
            if (first.startsWith("ffmpeg version")) {
                String[] parts = first.split("\\s+");
                if (parts.length >= 3) return parts[2];
            }
            if ("deno".equals(cmd) || "node".equals(cmd) || "pip".equals(cmd) || "pip3".equals(cmd)
                    || "python".equals(cmd) || "python3".equals(cmd) || "yt-dlp".equals(cmd)
                    || "yt-dlp-proxy".equals(cmd)) {
                Matcher m = VERSION_TOKEN.matcher(first);
                if (m.find()) return m.group(1);
            }
            return first.isBlank() ? out.trim() : first;
        } catch (Exception e) {
            return null;
        }
    }

    /** Compare yt-dlp calendar versions; treat equal/normalized match as up-to-date. */
    static boolean isYtDlpOutdated(String installed, String latest) {
        String a = normalizeYtDlpVersion(installed);
        String b = normalizeYtDlpVersion(latest);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return false;
        // Installed nightly/newer than GitHub tag → OK
        if (a.compareTo(b) > 0) return false;
        return !a.equals(b);
    }

    static String normalizeYtDlpVersion(String v) {
        if (v == null) return "";
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        Matcher m = VERSION_TOKEN.matcher(v);
        return m.find() ? m.group(1) : v;
    }

    private String fetchLatestYtDlpVersion() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MatthewTubeDownloader/1.5")
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
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) log.accept(line);
        }
        return p.waitFor();
    }

    private static String osName() { return System.getProperty("os.name", "").toLowerCase(); }

    public Map<String, String> installHints() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("yt-dlp",       "pip install -U --user yt-dlp   (or: yt-dlp -U)");
        m.put("yt-dlp-proxy", "pip install --user yt-dlp-proxy   then:  yt-dlp-proxy update");
        m.put("ffmpeg",       osName().contains("win") ? "winget install Gyan.FFmpeg"
                : osName().contains("mac") ? "brew install ffmpeg"
                : "sudo apt-get install ffmpeg");
        m.put("deno",         osName().contains("win") ? "winget install DenoLand.Deno"
                : osName().contains("mac") ? "brew install deno"
                : "curl -fsSL https://deno.land/install.sh | sh");
        m.put("node",         osName().contains("win") ? "winget install OpenJS.NodeJS.LTS"
                : osName().contains("mac") ? "brew install node"
                : "https://nodejs.org/ (or: sudo apt install nodejs)");
        m.put("bgutil-pot",   "pip install -U --user bgutil-ytdlp-pot-provider"
                + "  +  clone repo → ~/bgutil-ytdlp-pot-provider, cd server && npm install"
                + "  +  start HTTP server (entry server/src/main.ts): "
                + PotProviderHelper.httpServerStartCommand());
        m.put("python",       "https://python.org/downloads");
        m.put("pip",          "python -m ensurepip --upgrade");
        return m;
    }
}
