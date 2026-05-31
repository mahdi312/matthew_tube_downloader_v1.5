package com.mst.matt.matthew_tube_downloader.service.scheduler;

import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.model.VideoInfo;
import com.mst.matt.matthew_tube_downloader.service.MultiTypeDownloadRunner;
import com.mst.matt.matthew_tube_downloader.service.YtDlpService;
import com.mst.matt.matthew_tube_downloader.service.settings.AppSettings;
import com.mst.matt.matthew_tube_downloader.service.settings.SettingsManager;
import com.mst.matt.matthew_tube_downloader.service.strategy.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Central queue + scheduler.
 *
 * v1.4 additions on top of v1.3:
 *   - {@link #setMaxConcurrentDownloads(int)} : resize the runner pool live.
 *   - {@link #stopAll()}                       : pause every running item AND
 *                                                 prevent the scheduler tick
 *                                                 from starting new ones.
 *   - {@link #startAll()}                      : re-enable autostart and run
 *                                                 everything that is queued / paused.
 *   - {@link #restartFromScratch(QueueItem)}   : delete any partial files and
 *                                                 enqueue the item fresh.
 *   - {@link #restartAllFromScratch()}         : bulk restart-from-scratch.
 *
 * Pause/resume semantics (unchanged from v1.3):
 *   - yt-dlp / yt-dlp-proxy   : real pause via process kill, resume via {@code --continue}.
 *   - Invidious / PureJava /
 *     GitHubActions           : pause = hard cancel (user is warned in the UI).
 */
public class DownloadScheduler {

    private final ObservableList<QueueItem> items = FXCollections.observableArrayList();
    private final YtDlpService ytDlpService;

    private final ScheduledExecutorService schedulerThread =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MTD-Scheduler"); t.setDaemon(true); return t;
            });

    /** Resizable worker pool — recreated when the user changes max-concurrent. */
    private volatile ThreadPoolExecutor runner;
    private volatile int maxConcurrent;

    /** id -> running strategy (so we can abort on pause/cancel). */
    private final Map<String, DownloadStrategy> activeStrategies = new HashMap<>();
    /** id -> thread running the job (so we can interrupt). */
    private final Map<String, Thread> activeThreads = new HashMap<>();

    private Consumer<String> globalLog = s -> {};

    /**
     * v1.4: master switch. When false, the periodic tick will NOT auto-start
     * scheduled items. Pause/stop-all flips this to false.
     */
    private volatile boolean autostartEnabled = true;

    private volatile boolean running = false;

    public DownloadScheduler(YtDlpService ytDlpService) {
        this.ytDlpService = ytDlpService;
        AppSettings s = SettingsManager.load();
        this.maxConcurrent = Math.max(1, Math.min(8, s.maxConcurrentDownloads));
        this.runner = newRunnerPool(this.maxConcurrent);

        // v1.5: hydrate the queue from disk IMMEDIATELY (was previously only
        // done inside start(), which meant if enableSchedulerOnStart=false the
        // user's queue silently disappeared on every restart).
        List<QueueItem> persisted = QueuePersistence.load();
        if (!persisted.isEmpty()) {
            if (Platform.isFxApplicationThread()) {
                items.setAll(persisted);
            } else {
                Platform.runLater(() -> items.setAll(persisted));
            }
        }
    }

    public ObservableList<QueueItem> items() { return items; }
    public void setGlobalLog(Consumer<String> sink) { this.globalLog = sink; }

    public int getMaxConcurrentDownloads()  { return maxConcurrent; }
    public boolean isAutostartEnabled()     { return autostartEnabled; }

    /* ─────────────────── lifecycle ─────────────────── */

    public synchronized void start() {
        if (running) return;
        // v1.5: queue is already loaded in the constructor; just kick off the
        // periodic tick that fires scheduled items at their due time.
        running = true;
        schedulerThread.scheduleAtFixedRate(this::tick, 5, 15, TimeUnit.SECONDS);
        globalLog.accept("[scheduler] started — " + items.size() + " items in queue, max concurrent = " + maxConcurrent);
    }

    public synchronized void stop() {
        running = false;
        schedulerThread.shutdownNow();
        if (runner != null) runner.shutdownNow();
        for (DownloadStrategy s : activeStrategies.values()) {
            try { s.abort(); } catch (Exception ignored) {}
        }
    }

    /* ─────────────────── pool sizing (Feature B) ─────────────────── */

    /**
     * Resize the worker pool. Already-running jobs are NOT killed; the new
     * limit takes effect for the next tick.
     */
    public synchronized void setMaxConcurrentDownloads(int n) {
        int clamped = Math.max(1, Math.min(8, n));
        if (clamped == this.maxConcurrent) return;
        ThreadPoolExecutor old = this.runner;
        this.maxConcurrent = clamped;
        this.runner = newRunnerPool(clamped);
        // Don't shutdown old immediately - it may have in-flight jobs.
        // Mark it for orderly shutdown after current tasks complete.
        if (old != null) old.shutdown();
        globalLog.accept("[scheduler] max concurrent downloads -> " + clamped);

        // Persist user choice
        AppSettings s = SettingsManager.load();
        s.maxConcurrentDownloads = clamped;
        SettingsManager.save(s);
    }

    private ThreadPoolExecutor newRunnerPool(int size) {
        return (ThreadPoolExecutor) Executors.newFixedThreadPool(size, r -> {
            Thread t = new Thread(r, "MTD-Runner");
            t.setDaemon(true);
            return t;
        });
    }

    /* ─────────────────── add / remove / clear ─────────────────── */

    public synchronized void add(QueueItem item) {
        item.ensureTransients();
        Platform.runLater(() -> items.add(item));
        QueuePersistence.save(snapshot());
        globalLog.accept("[scheduler] added: " + item.label);
    }

    public synchronized void remove(QueueItem item) {
        abort(item);
        Platform.runLater(() -> items.remove(item));
        QueuePersistence.save(snapshot());
    }

    public synchronized void clearCompleted() {
        Platform.runLater(() -> items.removeIf(i ->
                i.status == QueueItem.Status.DONE || i.status == QueueItem.Status.CANCELLED));
        QueuePersistence.save(snapshot());
    }

    /* ─────────────────── run / pause / resume / cancel ─────────────────── */

    public synchronized void runNow(QueueItem item) {
        if (item.status == QueueItem.Status.RUNNING) return;
        item.setStatus(QueueItem.Status.QUEUED);
        item.setScheduledAt(System.currentTimeMillis());
        startItem(item);
    }

    /**
     * v1.4: Run every queued OR paused item. Re-enables autostart.
     * (Previously this only ran QUEUED items.)
     */
    public synchronized void runAllNow() {
        autostartEnabled = true;
        for (QueueItem it : List.copyOf(items)) {
            if (it.status == QueueItem.Status.QUEUED || it.status == QueueItem.Status.PAUSED) {
                runNow(it);
            }
        }
    }

    /** Try to pause an item. Returns false if the strategy can't pause gracefully. */
    public synchronized boolean pause(QueueItem item) {
        StrategyType t = item.config.getStrategy();
        boolean resumable = (t == StrategyType.YT_DLP_DIRECT || t == StrategyType.YT_DLP_PROXY);
        DownloadStrategy s = activeStrategies.get(item.id);
        if (s != null) try { s.abort(); } catch (Exception ignored) {}
        Thread th = activeThreads.get(item.id);
        if (th != null) th.interrupt();
        item.setStatus(QueueItem.Status.PAUSED);
        item.setLastMessage(resumable
                ? "Paused — will resume from last .part file."
                : "Aborted (strategy does not support pause/resume).");
        QueuePersistence.save(snapshot());
        return resumable;
    }

    public synchronized void resume(QueueItem item) {
        if (item.status != QueueItem.Status.PAUSED) return;
        startItem(item);
    }

    public synchronized void cancel(QueueItem item) {
        abort(item);
        item.setStatus(QueueItem.Status.CANCELLED);
        QueuePersistence.save(snapshot());
    }

    /* ─────────────────── v1.4: stop-all / start-all / restart-from-scratch ─────────────────── */

    /**
     * Stop ALL downloads. Running items are paused (or hard-cancelled for
     * non-resumable strategies). Autostart is disabled so the periodic tick
     * stops launching scheduled items until {@link #startAll()} is called.
     */
    public synchronized void stopAll() {
        autostartEnabled = false;
        int paused = 0, cancelled = 0;
        for (QueueItem it : List.copyOf(items)) {
            if (it.status == QueueItem.Status.RUNNING) {
                if (DownloadScheduler.strategySupportsPause(it.config.getStrategy())) {
                    pause(it); paused++;
                } else {
                    cancel(it); cancelled++;
                }
            }
        }
        globalLog.accept("[scheduler] STOP ALL — paused=" + paused + ", cancelled=" + cancelled
                + ". Autostart disabled.");
    }

    /**
     * Re-enable autostart and immediately fire every QUEUED / PAUSED item.
     * Equivalent to "Run all now" + flipping the autostart switch back on.
     */
    public synchronized void startAll() {
        autostartEnabled = true;
        int started = 0;
        for (QueueItem it : List.copyOf(items)) {
            if (it.status == QueueItem.Status.QUEUED
                    || it.status == QueueItem.Status.PAUSED) {
                startItem(it); started++;
            }
        }
        globalLog.accept("[scheduler] START ALL — launched " + started + " item(s). Autostart enabled.");
    }

    /**
     * "Download from scratch" semantics: wipe any partial files, reset progress,
     * mark QUEUED, and start immediately. The partial-file deletion is what
     * forces yt-dlp not to resume.
     */
    public synchronized void restartFromScratch(QueueItem item) {
        abort(item);
        deletePartials(item);
        item.setProgress(0.0);
        item.setLastMessage("Restarting from scratch…");
        item.setStatus(QueueItem.Status.QUEUED);
        item.setScheduledAt(System.currentTimeMillis());
        startItem(item);
    }

    public synchronized void restartAllFromScratch() {
        autostartEnabled = true;
        int n = 0;
        for (QueueItem it : List.copyOf(items)) {
            // Skip already-finished items
            if (it.status == QueueItem.Status.DONE) continue;
            restartFromScratch(it);
            n++;
        }
        globalLog.accept("[scheduler] RESTART ALL FROM SCRATCH — " + n + " item(s).");
    }

    /**
     * Delete the .part / .ytdl files yt-dlp leaves behind on a paused run,
     * so the next start downloads from byte 0. Best-effort: log and continue
     * on errors.
     */
    private void deletePartials(QueueItem item) {
        String dir = item.config.getOutputDir();
        if (dir == null || dir.isBlank()) return;
        Path p = Paths.get(dir);
        if (!Files.isDirectory(p)) return;
        try (var stream = Files.list(p)) {
            int[] count = {0};
            stream.forEach(f -> {
                String n = f.getFileName().toString();
                if (n.endsWith(".part") || n.endsWith(".ytdl")
                        || n.endsWith(".part-Frag1") || n.matches(".*\\.part-Frag\\d+$")) {
                    try { Files.deleteIfExists(f); count[0]++; }
                    catch (IOException ignored) {}
                }
            });
            if (count[0] > 0) {
                globalLog.accept("[scheduler] removed " + count[0] + " partial file(s) for: " + item.label);
            }
        } catch (IOException e) {
            globalLog.accept("[scheduler] could not scan " + dir + " for partials: " + e.getMessage());
        }
    }

    private void abort(QueueItem item) {
        DownloadStrategy s = activeStrategies.remove(item.id);
        if (s != null) try { s.abort(); } catch (Exception ignored) {}
        Thread th = activeThreads.remove(item.id);
        if (th != null) th.interrupt();
    }

    /* ─────────────────── core loop ─────────────────── */

    private void tick() {
        try {
            if (!autostartEnabled) return;            // v1.4: respect stop-all switch
            long now = System.currentTimeMillis();
            for (QueueItem it : List.copyOf(items)) {
                if (it.status == QueueItem.Status.QUEUED
                        && it.scheduledAt != null
                        && it.scheduledAt > 0
                        && it.scheduledAt <= now
                        && !activeThreads.containsKey(it.id)) {
                    globalLog.accept("[scheduler] starting due item: " + it.label);
                    startItem(it);
                }
            }
        } catch (Throwable t) {
            globalLog.accept("[scheduler] tick error: " + t.getMessage());
        }
    }

    /** Spawn a runner thread for one item. */
    private void startItem(QueueItem item) {
        synchronized (this) {
            if (activeThreads.containsKey(item.id)) return;
        }
        item.setStatus(QueueItem.Status.RUNNING);
        item.setLastMessage("Starting…");
        QueuePersistence.save(snapshot());

        Runnable job = () -> {
            DownloadStrategy strategy = build(item.config);
            synchronized (DownloadScheduler.this) {
                activeStrategies.put(item.id, strategy);
                activeThreads.put(item.id, Thread.currentThread());
            }
            try {
                Consumer<String> log = line -> {
                    item.setLastMessage(line);
                    globalLog.accept("[" + shortId(item.id) + "] " + line);
                };

                if (!strategy.isAvailable(item.config, log)) {
                    item.setStatus(QueueItem.Status.FAILED);
                    item.setLastMessage("Strategy unavailable.");
                    return;
                }

                VideoInfo dummy = new VideoInfo(1, item.label, "", "", item.config.getUrl());

                int exit = MultiTypeDownloadRunner.runAllTypes(
                        item.config, dummy, strategy, log,
                        (frac, label) -> {
                            if (frac != null && frac >= 0) item.setProgress(frac);
                            if (label != null && !label.isBlank()) item.setLastMessage(label);
                        },
                        () -> Thread.currentThread().isInterrupted());

                if (exit == 0) {
                    item.setProgress(1.0);
                    item.setStatus(QueueItem.Status.DONE);
                    item.setLastMessage("✓ Completed.");
                } else if (exit == -1) {
                    if (item.status != QueueItem.Status.PAUSED) {
                        item.setStatus(QueueItem.Status.CANCELLED);
                        item.setLastMessage("Cancelled.");
                    }
                } else {
                    item.setStatus(QueueItem.Status.FAILED);
                    item.setLastMessage("Exit code " + exit);
                }
            } catch (Throwable t) {
                item.setStatus(QueueItem.Status.FAILED);
                item.setLastMessage(t.getClass().getSimpleName() + ": " + t.getMessage());
                globalLog.accept("[scheduler] " + item.label + " → " + t);
            } finally {
                synchronized (DownloadScheduler.this) {
                    activeStrategies.remove(item.id);
                    activeThreads.remove(item.id);
                }
                QueuePersistence.save(snapshot());
            }
        };
        runner.submit(job);
    }

    private DownloadStrategy build(DownloadConfig config) {
        StrategyType t = config.getStrategy() != null ? config.getStrategy() : StrategyType.YT_DLP_DIRECT;
        return switch (t) {
            case YT_DLP_DIRECT   -> new YtDlpDirectStrategy(ytDlpService);
            case INVIDIOUS       -> new InvidiousStrategy();
            case PURE_JAVA       -> new PureJavaStrategy();
            case YT_DLP_PROXY    -> new YtDlpProxyStrategy(ytDlpService);
            case GITHUB_ACTIONS  -> new GitHubActionsStrategy();
            case SNI_INFO        -> new YtDlpDirectStrategy(ytDlpService);
        };
    }

    private List<QueueItem> snapshot() {
        return List.copyOf(items);
    }

    public static boolean strategySupportsPause(StrategyType t) {
        return t == StrategyType.YT_DLP_DIRECT || t == StrategyType.YT_DLP_PROXY;
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.substring(0, Math.min(8, id.length()));
    }
}
