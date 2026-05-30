package com.mst.matt.matthew_tube_downloader.service.strategy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.model.VideoInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Strategy 4: offload the download to a GitHub Actions runner.
 *
 * Assumes the user has forked a repo such as
 *   • https://github.com/ProAlit/aio-downloader, or
 *   • https://github.com/alitavakoli01/YouTubeDownloader
 * which exposes a {@code workflow_dispatch} workflow taking a YouTube URL as input.
 *
 * Flow:
 *   1. POST /actions/workflows/{file}/dispatches with the URL as input.
 *   2. Poll /actions/runs?event=workflow_dispatch&branch=… until the freshly-created
 *      run completes (status=completed, conclusion=success).
 *   3. GET /actions/runs/{id}/artifacts → archive_download_url.
 *   4. Download artifact ZIP, extract into output directory.
 *
 * Requires a personal access token (PAT) with at least {@code actions:write} and
 * {@code contents:read} scopes.
 */
public class GitHubActionsStrategy implements DownloadStrategy {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private volatile boolean cancelled;

    @Override
    public StrategyType type() { return StrategyType.GITHUB_ACTIONS; }

    @Override
    public boolean isAvailable(DownloadConfig config, Consumer<String> logSink) {
        if (config.getGithubRepo() == null || config.getGithubRepo().isBlank()) {
            logSink.accept("ERROR: GitHub repo not set (e.g. \"yourname/aio-downloader\").");
            return false;
        }
        if (config.getGithubToken() == null || config.getGithubToken().isBlank()) {
            logSink.accept("ERROR: GitHub Personal Access Token (PAT) not set.");
            logSink.accept("Create one at https://github.com/settings/tokens with scopes: actions:write, contents:read");
            return false;
        }
        return true;
    }

    @Override
    public int downloadOne(DownloadConfig config,
                           VideoInfo video,
                           Consumer<String> logSink,
                           BiConsumer<Double, String> progressSink,
                           BooleanSupplier cancelCheck) throws Exception {

        cancelled = false;
        String repo     = config.getGithubRepo().trim();
        String workflow = (config.getGithubWorkflow() == null || config.getGithubWorkflow().isBlank())
                ? "download.yml" : config.getGithubWorkflow().trim();
        String branch   = (config.getGithubBranch() == null || config.getGithubBranch().isBlank())
                ? "main" : config.getGithubBranch().trim();
        String token    = config.getGithubToken().trim();

        String url = (video != null && video.getUrl() != null && !video.getUrl().isBlank())
                ? video.getUrl() : config.getUrl();

        logSink.accept("[GitHub] repo=" + repo + " workflow=" + workflow + " branch=" + branch);
        progressSink.accept(0.02, "Triggering workflow…");

        // 1) Dispatch
        long beforeDispatch = System.currentTimeMillis();
        dispatchWorkflow(repo, workflow, branch, url, config, token, logSink);
        logSink.accept("[GitHub] workflow_dispatch sent ✓");

        // 2) Poll for new run
        long runId = waitForNewRun(repo, workflow, branch, beforeDispatch, token, logSink, progressSink, cancelCheck);
        if (runId < 0) return -1;
        logSink.accept("[GitHub] tracking run id=" + runId);

        // 3) Wait until that run completes
        String conclusion = waitForRunCompletion(repo, runId, token, logSink, progressSink, cancelCheck);
        if (conclusion == null) return -1;
        if (!"success".equalsIgnoreCase(conclusion)) {
            logSink.accept("[GitHub] run finished with conclusion=" + conclusion);
            return 2;
        }

        // 4) Download artifacts
        progressSink.accept(0.85, "Downloading artifact…");
        int n = downloadArtifacts(repo, runId, token, config.getOutputDir(), logSink, progressSink, cancelCheck);
        if (n < 0) return -1;
        if (n == 0) {
            logSink.accept("[GitHub] WARN: workflow succeeded but produced 0 artifacts.");
            return 3;
        }
        progressSink.accept(1.0, "Done — " + n + " artifact(s) extracted");
        return 0;
    }

    @Override
    public void abort() { cancelled = true; }

    /* ───────────────────────── GitHub API calls ───────────────────────── */

    private void dispatchWorkflow(String repo, String workflow, String branch, String videoUrl,
                                  DownloadConfig config, String token, Consumer<String> log) throws Exception {

        // Build inputs map. Most aio-downloader forks expect at minimum {"url": "..."}.
        // We also include a few common keys; unknown keys are simply ignored by GitHub.
        Map<String, String> inputs = new HashMap<>();
        inputs.put("url", videoUrl);
        inputs.put("video_url", videoUrl);  // alt key name some forks use
        inputs.put("link", videoUrl);
        if (config.getDownloadType() == DownloadConfig.DownloadType.AUDIO) {
            inputs.put("format", "mp3");
            inputs.put("mode", "audio");
        } else {
            inputs.put("format", "mp4");
            inputs.put("mode", "video");
        }
        if (config.getTargetHeight() > 0) {
            inputs.put("quality", config.getTargetHeight() + "p");
        }

        JsonObject body = new JsonObject();
        body.addProperty("ref", branch);
        JsonObject in = new JsonObject();
        inputs.forEach(in::addProperty);
        body.add("inputs", in);

        URI uri = URI.create("https://api.github.com/repos/" + repo
                + "/actions/workflows/" + workflow + "/dispatches");
        HttpRequest req = baseRequest(uri, token)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 204) {
            log.accept("[GitHub] dispatch HTTP " + resp.statusCode() + " — " + resp.body());
            throw new IOException("workflow_dispatch failed (HTTP " + resp.statusCode() + ")");
        }
    }

    private long waitForNewRun(String repo, String workflow, String branch, long since,
                               String token, Consumer<String> log, BiConsumer<Double, String> prog,
                               BooleanSupplier cancelCheck) throws Exception {
        URI uri = URI.create("https://api.github.com/repos/" + repo
                + "/actions/workflows/" + workflow + "/runs?event=workflow_dispatch&branch=" + branch
                + "&per_page=5");
        long deadline = System.currentTimeMillis() + 90_000L; // 90s to appear

        while (System.currentTimeMillis() < deadline) {
            if (cancelled || cancelCheck.getAsBoolean()) return -1;
            prog.accept(0.08, "Waiting for run to appear…");
            HttpResponse<String> resp = client.send(baseRequest(uri, token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonArray runs = root.getAsJsonArray("workflow_runs");
                for (JsonElement el : runs) {
                    JsonObject run = el.getAsJsonObject();
                    long id = run.get("id").getAsLong();
                    String created = run.get("created_at").getAsString();
                    long createdMs = java.time.Instant.parse(created).toEpochMilli();
                    if (createdMs >= since - 5_000) { // 5s slack
                        return id;
                    }
                }
            } else {
                log.accept("[GitHub] runs HTTP " + resp.statusCode());
            }
            Thread.sleep(3000);
        }
        log.accept("[GitHub] timed out waiting for the run to appear.");
        return -1;
    }

    private String waitForRunCompletion(String repo, long runId, String token,
                                        Consumer<String> log, BiConsumer<Double, String> prog,
                                        BooleanSupplier cancelCheck) throws Exception {
        URI uri = URI.create("https://api.github.com/repos/" + repo + "/actions/runs/" + runId);
        long start = System.currentTimeMillis();
        long maxMs = 30L * 60 * 1000; // 30 min hard cap
        int tick = 0;

        while (System.currentTimeMillis() - start < maxMs) {
            if (cancelled || cancelCheck.getAsBoolean()) {
                log.accept("[GitHub] cancelled while polling run.");
                return null;
            }
            HttpResponse<String> resp = client.send(baseRequest(uri, token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject run = JsonParser.parseString(resp.body()).getAsJsonObject();
                String status = run.get("status").getAsString();
                String concl  = run.get("conclusion").isJsonNull() ? null : run.get("conclusion").getAsString();
                String htmlUrl = run.get("html_url").getAsString();

                tick++;
                double f = 0.10 + Math.min(0.70, tick * 0.02); // creep from 10% → ~80%
                prog.accept(f, "Run status: " + status + (concl == null ? "" : " / " + concl));
                if ("completed".equalsIgnoreCase(status)) {
                    log.accept("[GitHub] run done — " + concl + " (" + htmlUrl + ")");
                    return concl == null ? "unknown" : concl;
                }
            } else {
                log.accept("[GitHub] run-poll HTTP " + resp.statusCode());
            }
            Thread.sleep(8000);
        }
        log.accept("[GitHub] run timed out after 30 minutes.");
        return null;
    }

    private int downloadArtifacts(String repo, long runId, String token, String outputDir,
                                  Consumer<String> log, BiConsumer<Double, String> prog,
                                  BooleanSupplier cancelCheck) throws Exception {
        URI uri = URI.create("https://api.github.com/repos/" + repo
                + "/actions/runs/" + runId + "/artifacts");
        HttpResponse<String> resp = client.send(baseRequest(uri, token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.accept("[GitHub] artifacts list HTTP " + resp.statusCode());
            return -1;
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray arts = root.getAsJsonArray("artifacts");
        if (arts == null || arts.size() == 0) return 0;

        Path outDir = Paths.get(outputDir != null ? outputDir : ".");
        Files.createDirectories(outDir);

        int count = 0;
        int total = arts.size();
        for (int i = 0; i < total; i++) {
            if (cancelled || cancelCheck.getAsBoolean()) return -1;
            JsonObject a = arts.get(i).getAsJsonObject();
            String name = a.get("name").getAsString();
            String dl   = a.get("archive_download_url").getAsString();
            long  size  = a.get("size_in_bytes").getAsLong();
            log.accept(String.format("[GitHub] artifact %d/%d: %s (%.2f MB)",
                    i + 1, total, name, size / 1_048_576.0));

            // Follow redirect, stream into temp ZIP, then unzip.
            Path tempZip = Files.createTempFile("ghart-", ".zip");
            HttpResponse<InputStream> zipResp = client.send(baseRequest(URI.create(dl), token).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (zipResp.statusCode() / 100 != 2) {
                log.accept("[GitHub] artifact download HTTP " + zipResp.statusCode());
                continue;
            }
            try (InputStream in = zipResp.body();
                 OutputStream out = Files.newOutputStream(tempZip,
                         java.nio.file.StandardOpenOption.CREATE,
                         java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long done = 0;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled || cancelCheck.getAsBoolean()) {
                        Files.deleteIfExists(tempZip);
                        return -1;
                    }
                    out.write(buf, 0, n);
                    done += n;
                    if (size > 0) {
                        double f = 0.85 + 0.13 * ((double) done / size);
                        prog.accept(f, String.format("%.1f MB / %.1f MB",
                                done / 1_048_576.0, size / 1_048_576.0));
                    }
                }
            }
            count += unzipInto(tempZip, outDir, log);
            Files.deleteIfExists(tempZip);
        }
        return count;
    }

    private int unzipInto(Path zip, Path outDir, Consumer<String> log) throws IOException {
        int extracted = 0;
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                Path target = outDir.resolve(e.getName()).normalize();
                if (!target.startsWith(outDir)) {
                    log.accept("[GitHub] skipping unsafe entry: " + e.getName());
                    continue;
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING);
                    extracted++;
                    log.accept("[GitHub]   extracted: " + outDir.relativize(target));
                }
                zin.closeEntry();
            }
        }
        return extracted;
    }

    /* ───────────────────────── util ───────────────────────── */

    private HttpRequest.Builder baseRequest(URI uri, String token) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "MatthewTubeDownloader/1.2");
    }
}
