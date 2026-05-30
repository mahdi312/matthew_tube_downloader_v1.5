package com.mst.matt.matthew_tube_downloader.controller;

import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.service.settings.AppSettings;
import com.mst.matt.matthew_tube_downloader.service.settings.SettingsManager;
import com.mst.matt.matthew_tube_downloader.service.settings.ThemeManager;
import com.mst.matt.matthew_tube_downloader.service.strategy.StrategyType;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.util.function.Consumer;

/**
 * Controller for the "Settings" tab — Feature 6.
 *
 * v1.5:
 *   • Replaces the v1.4 accent ColorPicker with a real theme {@link ComboBox}
 *     (Dracula / Jungle / White Snow). Switching themes live via
 *     {@link ThemeManager#apply}.
 *   • Save button now persists the chosen theme name to settings.json.
 */
public class SettingsController {

    @FXML private CheckBox enableProxyCheck;
    @FXML private CheckBox enableSchedulerCheck;
    @FXML private CheckBox autoCheckYtDlpCheck;
    @FXML private CheckBox rememberWindowSizeCheck;
    @FXML private Spinner<Integer> maxConcurrentSpinner;

    @FXML private ComboBox<StrategyType> defaultStrategyCombo;
    @FXML private TextField defaultOutputDirField;
    @FXML private ComboBox<DownloadConfig.DownloadType> defaultTypeCombo;
    @FXML private ComboBox<DownloadConfig.VideoQuality> defaultQualityCombo;
    @FXML private TextField defaultSubLangsField;
    @FXML private ComboBox<DownloadConfig.SubFormat> defaultSubFormatCombo;
    @FXML private ComboBox<DownloadConfig.SubType>   defaultSubTypeCombo;
    @FXML private CheckBox defaultEmbedSubsCheck;

    @FXML private CheckBox defaultUseProxyCheck;
    @FXML private TextField defaultProxyHostField;
    @FXML private TextField defaultProxyPortField;

    @FXML private TextField invidiousInstanceField;
    @FXML private CheckBox  invidiousAutoRotateCheck;
    @FXML private TextField githubRepoField;
    @FXML private TextField githubWorkflowField;
    @FXML private TextField githubBranchField;

    /** v1.5: theme combo replaces v1.4 ColorPicker. */
    @FXML private ComboBox<ThemeManager.Theme> themeCombo;
    @FXML private Label themePreviewLabel;
    @FXML private Label  statusLabel;

    private Consumer<AppSettings> onApplied = s -> {};

    public void setOnApplied(Consumer<AppSettings> sink) { this.onApplied = sink == null ? s -> {} : sink; }

    @FXML
    public void initialize() {
        defaultStrategyCombo.getItems().setAll(StrategyType.values());
        defaultTypeCombo.getItems().setAll(DownloadConfig.DownloadType.values());
        defaultQualityCombo.getItems().setAll(DownloadConfig.VideoQuality.values());
        defaultSubFormatCombo.getItems().setAll(DownloadConfig.SubFormat.values());
        defaultSubTypeCombo.getItems().setAll(DownloadConfig.SubType.values());
        maxConcurrentSpinner.setValueFactory(
                new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(1, 8, 2));

        // v1.5: theme combo
        themeCombo.getItems().setAll(ThemeManager.Theme.values());

        // Live-preview: switch theme as the user changes the combo. Saved on Save.
        themeCombo.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                ThemeManager.apply(newV);
                themePreviewLabel.setText("Preview applied: " + newV.displayName
                        + " — click Save to persist, or Reload to revert.");
            }
        });

        load(SettingsManager.load());
    }

    /* ───────────────────────── load / save / reset ───────────────────────── */

    private void load(AppSettings s) {
        enableProxyCheck.setSelected(s.enableProxySection);
        enableSchedulerCheck.setSelected(s.enableSchedulerOnStart);
        autoCheckYtDlpCheck.setSelected(s.autoCheckYtDlpUpdates);
        rememberWindowSizeCheck.setSelected(s.rememberWindowSize);
        if (maxConcurrentSpinner.getValueFactory() != null) {
            maxConcurrentSpinner.getValueFactory().setValue(Math.max(1, Math.min(8, s.maxConcurrentDownloads)));
        }

        defaultStrategyCombo.setValue(s.defaultStrategy);
        defaultOutputDirField.setText(s.defaultOutputDir);
        defaultTypeCombo.setValue(s.defaultDownloadType);
        defaultQualityCombo.setValue(s.defaultQuality);
        defaultSubLangsField.setText(s.defaultSubtitleLangs);
        defaultSubFormatCombo.setValue(s.defaultSubFormat);
        defaultSubTypeCombo.setValue(s.defaultSubType);
        defaultEmbedSubsCheck.setSelected(s.defaultEmbedSubs);

        defaultUseProxyCheck.setSelected(s.defaultUseProxy);
        defaultProxyHostField.setText(s.defaultProxyHost);
        defaultProxyPortField.setText(s.defaultProxyPort);

        invidiousInstanceField.setText(s.invidiousDefaultInstance);
        invidiousAutoRotateCheck.setSelected(s.invidiousDefaultAutoRotate);
        githubRepoField.setText(s.githubDefaultRepo);
        githubWorkflowField.setText(s.githubDefaultWorkflow);
        githubBranchField.setText(s.githubDefaultBranch);

        // v1.5: theme
        themeCombo.setValue(ThemeManager.Theme.fromName(s.themeName));
        themePreviewLabel.setText("Active theme: " + themeCombo.getValue().displayName);

        statusLabel.setText("Loaded from " + SettingsManager.settingsFile());
    }

    @FXML
    public void onSave() {
        AppSettings s = SettingsManager.load();
        s.enableProxySection      = enableProxyCheck.isSelected();
        s.enableSchedulerOnStart  = enableSchedulerCheck.isSelected();
        s.autoCheckYtDlpUpdates   = autoCheckYtDlpCheck.isSelected();
        s.rememberWindowSize      = rememberWindowSizeCheck.isSelected();
        if (maxConcurrentSpinner.getValue() != null)
            s.maxConcurrentDownloads = maxConcurrentSpinner.getValue();

        if (defaultStrategyCombo.getValue() != null)
            s.defaultStrategy = defaultStrategyCombo.getValue();
        s.defaultOutputDir        = defaultOutputDirField.getText().trim();
        if (defaultTypeCombo.getValue() != null)
            s.defaultDownloadType = defaultTypeCombo.getValue();
        if (defaultQualityCombo.getValue() != null)
            s.defaultQuality      = defaultQualityCombo.getValue();
        s.defaultSubtitleLangs    = defaultSubLangsField.getText().trim();
        if (defaultSubFormatCombo.getValue() != null)
            s.defaultSubFormat    = defaultSubFormatCombo.getValue();
        if (defaultSubTypeCombo.getValue() != null)
            s.defaultSubType      = defaultSubTypeCombo.getValue();
        s.defaultEmbedSubs        = defaultEmbedSubsCheck.isSelected();

        s.defaultUseProxy         = defaultUseProxyCheck.isSelected();
        s.defaultProxyHost        = defaultProxyHostField.getText().trim();
        s.defaultProxyPort        = defaultProxyPortField.getText().trim();

        s.invidiousDefaultInstance   = invidiousInstanceField.getText().trim();
        s.invidiousDefaultAutoRotate = invidiousAutoRotateCheck.isSelected();
        s.githubDefaultRepo          = githubRepoField.getText().trim();
        s.githubDefaultWorkflow      = githubWorkflowField.getText().trim();
        s.githubDefaultBranch        = githubBranchField.getText().trim();

        // v1.5: theme
        ThemeManager.Theme t = themeCombo.getValue();
        if (t != null) {
            s.themeName = t.name();
            ThemeManager.apply(t);
        }

        SettingsManager.save(s);
        statusLabel.setText("Saved ✓");
        onApplied.accept(s);
    }

    @FXML
    public void onReload() { load(SettingsManager.load()); ThemeManager.applyFromSettings(); }

    @FXML
    public void onReset() {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Reset all settings to defaults?", ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText("Reset settings");
        if (a.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        AppSettings fresh = new AppSettings();
        SettingsManager.save(fresh);
        load(fresh);
        ThemeManager.applyFromSettings();
        statusLabel.setText("Reset to defaults ✓");
        onApplied.accept(fresh);
    }

    @FXML
    public void onBrowseOutput() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Default output directory");
        String current = defaultOutputDirField.getText();
        if (current != null && !current.isBlank()) {
            File f = new File(current);
            if (f.exists()) chooser.setInitialDirectory(f);
        }
        File sel = chooser.showDialog(defaultOutputDirField.getScene().getWindow());
        if (sel != null) defaultOutputDirField.setText(sel.getAbsolutePath());
    }
}
