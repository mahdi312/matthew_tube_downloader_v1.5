module com.mst.matt.matthew_tube_downloader {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires java.desktop;
    requires java.net.http;          // Invidious / GitHub Actions / Dependency manager
    requires com.google.gson;

    opens com.mst.matt.matthew_tube_downloader to javafx.fxml;
    opens com.mst.matt.matthew_tube_downloader.controller to javafx.fxml;
    opens com.mst.matt.matthew_tube_downloader.model to javafx.base, com.google.gson;
    opens com.mst.matt.matthew_tube_downloader.service.dependency to javafx.base, javafx.fxml;
    opens com.mst.matt.matthew_tube_downloader.service.extractor  to javafx.base, javafx.fxml;
    opens com.mst.matt.matthew_tube_downloader.service.scheduler  to javafx.base, javafx.fxml, com.google.gson;
    opens com.mst.matt.matthew_tube_downloader.service.settings   to javafx.base, com.google.gson;

    exports com.mst.matt.matthew_tube_downloader;
    exports com.mst.matt.matthew_tube_downloader.controller;
    exports com.mst.matt.matthew_tube_downloader.model;
    exports com.mst.matt.matthew_tube_downloader.service;
    exports com.mst.matt.matthew_tube_downloader.service.strategy;
    exports com.mst.matt.matthew_tube_downloader.service.dependency;
    exports com.mst.matt.matthew_tube_downloader.service.extractor;
    exports com.mst.matt.matthew_tube_downloader.service.scheduler;
    exports com.mst.matt.matthew_tube_downloader.service.settings;
}
