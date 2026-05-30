package com.mst.matt.matthew_tube_downloader.model;

import javafx.application.Platform;
import javafx.beans.property.*;

/**
 * Represents a single video entry (from a playlist or standalone).
 *
 * v1.4 — like {@code ToolStatus} and {@code QueueItem}, every setter that
 * writes to a JavaFX property is now dispatched to the FX Application Thread.
 * This makes the class safe to call from background threads (DownloadTask,
 * scheduler workers) without manual {@code Platform.runLater} wrapping.
 */
public class VideoInfo {

    private final IntegerProperty index = new SimpleIntegerProperty();
    private final StringProperty title = new SimpleStringProperty();
    private final StringProperty id = new SimpleStringProperty();
    private final StringProperty duration = new SimpleStringProperty();
    private final StringProperty url = new SimpleStringProperty();
    private final BooleanProperty selected = new SimpleBooleanProperty(true);
    private final StringProperty status = new SimpleStringProperty("Pending");
    private final DoubleProperty progress = new SimpleDoubleProperty(0.0);

    public VideoInfo() {}

    public VideoInfo(int index, String title, String id, String duration, String url) {
        // Constructor runs on FX thread (controllers, scheduler init) so direct .set() is fine
        this.index.set(index);
        this.title.set(title);
        this.id.set(id);
        this.duration.set(duration);
        this.url.set(url);
    }

    public int getIndex() { return index.get(); }
    public void setIndex(int v) { runFx(() -> index.set(v)); }
    public IntegerProperty indexProperty() { return index; }

    public String getTitle() { return title.get(); }
    public void setTitle(String v) { runFx(() -> title.set(v)); }
    public StringProperty titleProperty() { return title; }

    public String getId() { return id.get(); }
    public void setId(String v) { runFx(() -> id.set(v)); }
    public StringProperty idProperty() { return id; }

    public String getDuration() { return duration.get(); }
    public void setDuration(String v) { runFx(() -> duration.set(v)); }
    public StringProperty durationProperty() { return duration; }

    public String getUrl() { return url.get(); }
    public void setUrl(String v) { runFx(() -> url.set(v)); }
    public StringProperty urlProperty() { return url; }

    public boolean isSelected() { return selected.get(); }
    public void setSelected(boolean v) { runFx(() -> selected.set(v)); }
    public BooleanProperty selectedProperty() { return selected; }

    public String getStatus() { return status.get(); }
    public void setStatus(String v) { runFx(() -> status.set(v)); }
    public StringProperty statusProperty() { return status; }

    public double getProgress() { return progress.get(); }
    public void setProgress(double v) { runFx(() -> progress.set(v)); }
    public DoubleProperty progressProperty() { return progress; }

    @Override
    public String toString() {
        return index.get() + ". " + title.get();
    }

    private static void runFx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }
}
