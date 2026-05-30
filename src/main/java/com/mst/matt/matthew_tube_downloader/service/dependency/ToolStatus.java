package com.mst.matt.matthew_tube_downloader.service.dependency;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * One row in the Tools tab: name, command, version (or "not installed"),
 * status emoji, and an action label for the button (Install / Update / Reinstall).
 *
 * v1.4 fix: every setter that touches a JavaFX property is now dispatched
 * to the JavaFX Application Thread via {@link Platform#runLater(Runnable)}.
 * This fixes the IllegalStateException ("Not on FX application thread")
 * that happened when DependencyManager.refresh() ran on a background thread
 * and wrote into bound StringProperty values.
 */
public class ToolStatus {

    public enum State { OK, OUTDATED, MISSING, UNKNOWN }

    private final StringProperty name        = new SimpleStringProperty();
    private final StringProperty command     = new SimpleStringProperty();
    private final StringProperty version     = new SimpleStringProperty("…");
    private final StringProperty latest      = new SimpleStringProperty("");
    private final StringProperty statusLabel = new SimpleStringProperty("Checking…");
    private final StringProperty actionLabel = new SimpleStringProperty("Check");
    private volatile State state = State.UNKNOWN;

    public ToolStatus(String name, String command) {
        // Constructor runs on the FX thread (controller initialize), no dispatch needed.
        this.name.set(name);
        this.command.set(command);
    }

    public String getName()                  { return name.get(); }
    public StringProperty nameProperty()     { return name; }

    public String getCommand()               { return command.get(); }
    public StringProperty commandProperty()  { return command; }

    public String getVersion()               { return version.get(); }
    public void setVersion(String v)         { runFx(() -> version.set(v)); }
    public StringProperty versionProperty()  { return version; }

    public String getLatest()                { return latest.get(); }
    public void setLatest(String v)          { runFx(() -> latest.set(v)); }
    public StringProperty latestProperty()   { return latest; }

    public String getStatusLabel()           { return statusLabel.get(); }
    public void setStatusLabel(String v)     { runFx(() -> statusLabel.set(v)); }
    public StringProperty statusLabelProperty() { return statusLabel; }

    public String getActionLabel()           { return actionLabel.get(); }
    public void setActionLabel(String v)     { runFx(() -> actionLabel.set(v)); }
    public StringProperty actionLabelProperty() { return actionLabel; }

    public State getState() { return state; }

    /**
     * Apply a full state-snapshot. All UI-bound mutations are performed
     * inside a single {@link Platform#runLater(Runnable)} so the FX scene
     * graph sees a consistent change atomically.
     */
    public void apply(State s, String version, String latest) {
        runFx(() -> {
            this.state = s;
            if (version != null) this.version.set(version);
            if (latest  != null) this.latest.set(latest);
            switch (s) {
                case OK       -> { statusLabel.set("✓ OK");              actionLabel.set("Reinstall / Update"); }
                case OUTDATED -> { statusLabel.set("⚠ Update available"); actionLabel.set("Update");             }
                case MISSING  -> { statusLabel.set("✗ Not installed");    actionLabel.set("Install");            }
                case UNKNOWN  -> { statusLabel.set("? Unknown");          actionLabel.set("Check");              }
            }
        });
    }

    /** Run on FX thread — synchronously if we're already there, else dispatch. */
    private static void runFx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }
}
