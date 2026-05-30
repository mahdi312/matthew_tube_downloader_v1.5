package com.mst.matt.matthew_tube_downloader.controller;

import com.mst.matt.matthew_tube_downloader.service.dependency.DependencyManager;
import com.mst.matt.matthew_tube_downloader.service.dependency.ToolStatus;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.TableColumn.CellDataFeatures;
import javafx.scene.layout.HBox;
import javafx.util.Callback;

import java.util.Map;

/**
 * Controller for the "Tools" tab — Feature 1.
 *
 * Shows status of yt-dlp / yt-dlp-proxy / ffmpeg / python / pip, lets the user
 * update or install each one. Every action confirms with a dialog before running.
 */
public class ToolsController {

    @FXML private TableView<ToolStatus> toolsTable;
    @FXML private TableColumn<ToolStatus, String> colTool;
    @FXML private TableColumn<ToolStatus, String> colCommand;
    @FXML private TableColumn<ToolStatus, String> colVersion;
    @FXML private TableColumn<ToolStatus, String> colLatest;
    @FXML private TableColumn<ToolStatus, String> colStatus;
    @FXML private TableColumn<ToolStatus, Void>   colAction;
    @FXML private TextArea hintsArea;
    @FXML private TextArea logArea;
    @FXML private Button refreshBtn;
    @FXML private Button updateYtDlpBtn;

    private final DependencyManager deps = new DependencyManager();
    private final ObservableList<ToolStatus> rows = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        rows.setAll(deps.tools());
        toolsTable.setItems(rows);

        colTool.setCellValueFactory   (cd -> cd.getValue().nameProperty());
        colCommand.setCellValueFactory(cd -> cd.getValue().commandProperty());
        colVersion.setCellValueFactory(cd -> cd.getValue().versionProperty());
        colLatest.setCellValueFactory (cd -> cd.getValue().latestProperty());
        colStatus.setCellValueFactory (cd -> cd.getValue().statusLabelProperty());

        // Action column → one button per row
        colAction.setCellFactory(buildActionCellFactory());

        // Initial population of install hints
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : deps.installHints().entrySet()) {
            sb.append(String.format("• %-14s %s%n", e.getKey() + ":", e.getValue()));
        }
        hintsArea.setText(sb.toString());

        // Trigger a refresh in the background on first show
        onRefreshAll();
    }

    private Callback<TableColumn<ToolStatus, Void>, TableCell<ToolStatus, Void>> buildActionCellFactory() {
        return col -> new TableCell<>() {
            private final Button btn = new Button("…");
            private final HBox box = new HBox(btn);
            {
                btn.getStyleClass().add("small-btn");
                btn.setOnAction(e -> {
                    ToolStatus t = getTableView().getItems().get(getIndex());
                    onActionClicked(t);
                });
                box.setSpacing(4);
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                } else {
                    ToolStatus t = getTableView().getItems().get(getIndex());
                    btn.textProperty().bind(t.actionLabelProperty());
                    setGraphic(box);
                }
            }
        };
    }

    /* ───────────────────────── actions ───────────────────────── */

    @FXML
    public void onRefreshAll() {
        log("Refreshing all tools…");
        refreshBtn.setDisable(true);
        Task<Void> t = new Task<>() {
            @Override protected Void call() {
                deps.refreshAll(rows, msg -> Platform.runLater(() -> log(msg)));
                return null;
            }
        };
        t.setOnSucceeded(e -> {
            refreshBtn.setDisable(false);
            log("Refresh complete.");
        });
        t.setOnFailed(e -> {
            refreshBtn.setDisable(false);
            log("Refresh failed: " + (t.getException() != null ? t.getException().getMessage() : ""));
        });
        new Thread(t, "tools-refresh").start();
    }

    @FXML
    public void onUpdateYtDlp() {
        ToolStatus ytDlp = rows.stream().filter(r -> "yt-dlp".equals(r.getName())).findFirst().orElse(null);
        if (ytDlp == null) return;
        onActionClicked(ytDlp);
    }

    @FXML
    public void onClearLog() { logArea.clear(); }

    private void onActionClicked(ToolStatus tool) {
        // Confirm dialog
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(tool.getActionLabel() + " — " + tool.getName());
        confirm.setHeaderText(tool.getActionLabel() + " " + tool.getName() + "?");
        String body;
        switch (tool.getState()) {
            case OUTDATED -> body = "Installed: " + tool.getVersion()
                    + "\nLatest:    " + tool.getLatest()
                    + "\n\nThe app will try `yt-dlp -U` first, then fall back to pip.";
            case MISSING  -> body = "This tool is not installed.\n\nThe app will try the best installer\n"
                    + "for your OS (winget / brew / apt / pip) and stream the output below.";
            default       -> body = "This will run the install/update command for " + tool.getName()
                    + ".\n\nProceed?";
        }
        confirm.setContentText(body);
        confirm.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        log("→ " + tool.getActionLabel() + " " + tool.getName() + " …");
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                return deps.install(tool, msg -> Platform.runLater(() -> log(msg)));
            }
        };
        task.setOnSucceeded(e -> {
            int code = task.getValue();
            log(code == 0 ? "✓ Done." : "Exited with code " + code);
            deps.refresh(tool, msg -> Platform.runLater(() -> log(msg)));
        });
        task.setOnFailed(e -> log("Failed: " + (task.getException() != null ? task.getException().getMessage() : "")));
        new Thread(task, "tools-action").start();
    }

    private void log(String message) {
        if (Platform.isFxApplicationThread()) {
            logArea.appendText(message + "\n");
        } else {
            Platform.runLater(() -> logArea.appendText(message + "\n"));
        }
    }
}
