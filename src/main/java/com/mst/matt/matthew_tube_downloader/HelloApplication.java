package com.mst.matt.matthew_tube_downloader;

import com.mst.matt.matthew_tube_downloader.controller.RootController;
import com.mst.matt.matthew_tube_downloader.service.scheduler.DownloadScheduler;
import com.mst.matt.matthew_tube_downloader.service.settings.AppSettings;
import com.mst.matt.matthew_tube_downloader.service.settings.SettingsManager;
import com.mst.matt.matthew_tube_downloader.service.settings.ThemeManager;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;

/**
 * Main JavaFX Application for Matthew Tube Downloader.
 *
 * v1.5 — theme switching wired through {@link ThemeManager}, UTF-8 charset
 * defaults forced before any child process spawns, and the quality-picker
 * dialog now picks up the active theme automatically.
 */
public class HelloApplication extends Application {

    private DownloadScheduler scheduler;

    @Override
    public void start(Stage stage) throws Exception {
        // v1.5: force UTF-8 everywhere — fixes Persian/Arabic/CJK titles
        // when the JVM was launched without -Dfile.encoding=UTF-8.
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        System.setProperty("stdout.encoding",  "UTF-8");
        System.setProperty("stderr.encoding",  "UTF-8");

        AppSettings settings = SettingsManager.load();

        FXMLLoader rootLoader = new FXMLLoader(
                HelloApplication.class.getResource("root-view.fxml"));
        Scene scene = new Scene(rootLoader.load(),
                settings.rememberWindowSize ? settings.windowWidth : 1100,
                settings.rememberWindowSize ? settings.windowHeight : 800);

        // v1.5: register the scene with ThemeManager BEFORE bootstrap so
        // any child views created on initial load already see the right theme.
        ThemeManager.register(scene);
        ThemeManager.applyFromSettings();

        RootController root = rootLoader.getController();
        root.bootstrap();
        this.scheduler = root.getScheduler();

        stage.setTitle("Matthew Tube Downloader v1.5");
        stage.setMinWidth(900);
        stage.setMinHeight(640);
        stage.setScene(scene);

        // Save window size on close if "remember" is on
        stage.widthProperty().addListener((o, a, b) -> {
            AppSettings s = SettingsManager.load();
            if (s.rememberWindowSize) { s.windowWidth = b.doubleValue(); SettingsManager.save(s); }
        });
        stage.heightProperty().addListener((o, a, b) -> {
            AppSettings s = SettingsManager.load();
            if (s.rememberWindowSize) { s.windowHeight = b.doubleValue(); SettingsManager.save(s); }
        });

        // Try to load icon
        try (InputStream iconStream = HelloApplication.class.getResourceAsStream("icon.png")) {
            if (iconStream != null) stage.getIcons().add(new Image(iconStream));
        } catch (Exception ignored) {}

        stage.setOnCloseRequest(evt -> {
            if (scheduler != null) scheduler.stop();
        });
        stage.show();
    }

    @Override
    public void stop() {
        if (scheduler != null) scheduler.stop();
    }
}
