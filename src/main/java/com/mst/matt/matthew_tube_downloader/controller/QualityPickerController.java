package com.mst.matt.matthew_tube_downloader.controller;

import com.mst.matt.matthew_tube_downloader.service.extractor.FormatInfo;
import com.mst.matt.matthew_tube_downloader.service.extractor.WebpageExtractor;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;

/**
 * Modal dialog that shows the list of formats discovered by {@link WebpageExtractor}
 * and lets the user pick one. Used by the Download tab when the URL is NOT a
 * YouTube URL (Feature 3).
 */
public class QualityPickerController {

    @FXML private Label headerLabel;
    @FXML private TableView<FormatInfo> formatsTable;
    @FXML private TableColumn<FormatInfo, String> colId;
    @FXML private TableColumn<FormatInfo, String> colKind;
    @FXML private TableColumn<FormatInfo, String> colResolution;
    @FXML private TableColumn<FormatInfo, String> colFps;
    @FXML private TableColumn<FormatInfo, String> colCodec;
    @FXML private TableColumn<FormatInfo, String> colExt;
    @FXML private TableColumn<FormatInfo, String> colBitrate;
    @FXML private TableColumn<FormatInfo, String> colSize;
    @FXML private TableColumn<FormatInfo, String> colNote;
    @FXML private Label footerLabel;
    @FXML private Button okBtn;

    private final ObservableList<FormatInfo> data = FXCollections.observableArrayList();
    private FormatInfo result;

    @FXML
    public void initialize() {
        formatsTable.setItems(data);
        colId.setCellValueFactory        (cd -> cd.getValue().formatIdProperty());
        colKind.setCellValueFactory      (cd -> cd.getValue().kindProperty());
        colResolution.setCellValueFactory(cd -> cd.getValue().resolutionProperty());
        colFps.setCellValueFactory       (cd -> cd.getValue().fpsProperty());
        colCodec.setCellValueFactory     (cd -> cd.getValue().codecProperty());
        colExt.setCellValueFactory       (cd -> cd.getValue().containerProperty());
        colBitrate.setCellValueFactory   (cd -> cd.getValue().bitrateProperty());
        colSize.setCellValueFactory      (cd -> cd.getValue().filesizeProperty());
        colNote.setCellValueFactory      (cd -> cd.getValue().noteProperty());

        formatsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) ->
                okBtn.setDisable(newV == null));
        okBtn.setDisable(true);
    }

    public void setResult(WebpageExtractor.ExtractResult result) {
        String enginePart = result.engine == null ? "?" : result.engine;
        headerLabel.setText("Source: " + (result.title == null ? result.url : result.title)
                + "    [extractor: " + enginePart + "]");
        data.setAll(result.formats);
        footerLabel.setText(result.formats.size() + " formats found.");
        if (!data.isEmpty()) formatsTable.getSelectionModel().selectFirst();
    }

    public FormatInfo result() { return result; }

    @FXML
    public void onOk() {
        result = formatsTable.getSelectionModel().getSelectedItem();
        ((Stage) okBtn.getScene().getWindow()).close();
    }

    @FXML
    public void onCancel() {
        result = null;
        ((Stage) okBtn.getScene().getWindow()).close();
    }
}
