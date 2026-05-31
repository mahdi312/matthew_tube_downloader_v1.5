package com.mst.matt.matthew_tube_downloader;

import javafx.application.Application;

public class Launcher {
    public static void main(String[] args) {
        // Must run before JavaFX / any child process — Windows defaults to cp1252
        // and corrupts Farsi/Persian titles from yt-dlp unless we force UTF-8 here.
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        System.setProperty("stdout.encoding", "UTF-8");
        System.setProperty("stderr.encoding", "UTF-8");
        Application.launch(HelloApplication.class, args);
    }
}
