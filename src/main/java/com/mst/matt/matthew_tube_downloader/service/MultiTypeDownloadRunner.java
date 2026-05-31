package com.mst.matt.matthew_tube_downloader.service;

import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.model.VideoInfo;
import com.mst.matt.matthew_tube_downloader.service.strategy.DownloadStrategy;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Runs each selected download type (video / audio / subtitles) sequentially. */
public final class MultiTypeDownloadRunner {

    private MultiTypeDownloadRunner() {}

    /**
     * @return worst non-zero exit code, {@code -1} if cancelled, {@code 0} if all passes succeeded
     */
    public static int runAllTypes(DownloadConfig config,
                                  VideoInfo video,
                                  DownloadStrategy strategy,
                                  Consumer<String> log,
                                  BiConsumer<Double, String> progress,
                                  BooleanSupplier cancelled) throws Exception {
        List<DownloadConfig.DownloadType> types = config.getSelectedDownloadTypes();
        if (types.isEmpty()) return 99;

        int worst = 0;
        for (int t = 0; t < types.size(); t++) {
            if (cancelled.getAsBoolean()) return -1;

            DownloadConfig.DownloadType type = types.get(t);
            if (types.size() > 1) {
                log.accept("Pass " + (t + 1) + "/" + types.size() + ": " + type);
            }

            DownloadConfig pass = config.duplicate();
            pass.setDownloadType(type);
            if (type != DownloadConfig.DownloadType.VIDEO) {
                pass.setPickedFormatId("");
            }

            int exit = strategy.downloadOne(
                    pass, video, log, progress, cancelled);

            if (exit == -1) return -1;
            if (exit != 0) worst = exit;
        }
        return worst;
    }
}
