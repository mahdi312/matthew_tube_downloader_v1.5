package com.mst.matt.matthew_tube_downloader.service.scheduler;

import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;

import java.util.UUID;

/**
 * One scheduled / queued download.
 *
 * Persisted to {@code queue.json} via Gson. JavaFX properties are recreated
 * on load (annotated {@code transient} so Gson skips them and rebuilds them
 * in {@link #ensureTransients()}).
 *
 * v1.4 fix: every mutator that updates a JavaFX property is now dispatched
 * to the FX Application Thread, matching the rule that scene-graph-bound
 * properties may only be written on the FX thread.
 */
public class QueueItem {

    public enum Status { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

    /** Stable identifier used for log-correlation and persistence keys. */
    public String id = UUID.randomUUID().toString();

    /** Friendly title/URL the UI shows. */
    public String label = "";

    /** The frozen DownloadConfig snapshot — strategy, quality, proxy, output dir, …  */
    public DownloadConfig config = new DownloadConfig();

    /** When to start. Null/0 = manual start only. Epoch milliseconds. */
    public Long scheduledAt;

    /** Current status. */
    public Status status = Status.QUEUED;

    /** Last log line / error message. */
    public String lastMessage = "";

    /** 0..1 progress for the UI. */
    public double progress = 0.0;

    /** Created-at epoch millis. */
    public long createdAt = System.currentTimeMillis();

    /* ── transient JavaFX bindings (not serialized) ── */
    private transient SimpleStringProperty statusLabel;
    private transient SimpleStringProperty progressLabel;
    private transient SimpleStringProperty scheduledLabel;
    private transient SimpleDoubleProperty progressDouble;
    private transient SimpleStringProperty messageLabel;

    public void ensureTransients() {
        if (statusLabel == null)    statusLabel = new SimpleStringProperty(status.name());
        if (progressLabel == null)  progressLabel = new SimpleStringProperty(String.format("%.0f%%", progress * 100));
        if (scheduledLabel == null) scheduledLabel = new SimpleStringProperty(formatScheduled());
        if (progressDouble == null) progressDouble = new SimpleDoubleProperty(progress);
        if (messageLabel == null)   messageLabel = new SimpleStringProperty(lastMessage == null ? "" : lastMessage);
    }

    public SimpleStringProperty statusLabelProperty()    { ensureTransients(); return statusLabel; }
    public SimpleStringProperty progressLabelProperty()  { ensureTransients(); return progressLabel; }
    public SimpleStringProperty scheduledLabelProperty() { ensureTransients(); return scheduledLabel; }
    public SimpleDoubleProperty progressDoubleProperty() { ensureTransients(); return progressDouble; }
    public SimpleStringProperty messageLabelProperty()   { ensureTransients(); return messageLabel; }

    public void setStatus(Status s) {
        this.status = s;
        ensureTransients();
        runFx(() -> statusLabel.set(s.name()));
    }

    public void setProgress(double p) {
        this.progress = p;
        ensureTransients();
        runFx(() -> {
            progressDouble.set(p);
            progressLabel.set(String.format("%.0f%%", p * 100));
        });
    }

    public void setLastMessage(String m) {
        this.lastMessage = m == null ? "" : m;
        ensureTransients();
        runFx(() -> messageLabel.set(this.lastMessage));
    }

    public void setScheduledAt(Long at) {
        this.scheduledAt = at;
        ensureTransients();
        runFx(() -> scheduledLabel.set(formatScheduled()));
    }

    private String formatScheduled() {
        if (scheduledAt == null || scheduledAt <= 0) return "manual";
        return java.time.Instant.ofEpochMilli(scheduledAt)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private static void runFx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }
}
