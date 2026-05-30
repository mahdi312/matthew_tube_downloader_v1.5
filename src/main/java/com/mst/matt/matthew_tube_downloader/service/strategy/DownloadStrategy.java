package com.mst.matt.matthew_tube_downloader.service.strategy;

import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.model.VideoInfo;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Contract every download strategy must satisfy.
 *
 * Implementations are stateless apart from configuration that comes in via
 * {@link DownloadConfig}; the JavaFX {@code DownloadTask} owns lifecycle.
 *
 * @see StrategyType
 */
public interface DownloadStrategy {

    /** Which strategy this implementation handles. */
    StrategyType type();

    /**
     * Download <b>one</b> video.
     *
     * @param config        full user configuration (URL, output dir, quality, proxy…)
     * @param video         specific entry to download (for single-video mode, the playlist
     *                      index can be 1 and url comes from {@code config.getUrl()}).
     * @param logSink       streamed log lines for the UI
     * @param progressSink  (fraction 0..1, status string) updates for the per-video progress bar
     * @param cancelCheck   strategy must poll this between long steps and abort if true
     * @return process exit code (0 = success, non-zero = failure)
     * @throws Exception on hard errors
     */
    int downloadOne(DownloadConfig config,
                    VideoInfo video,
                    Consumer<String> logSink,
                    BiConsumer<Double, String> progressSink,
                    java.util.function.BooleanSupplier cancelCheck) throws Exception;

    /**
     * Best-effort preflight check (can the strategy actually work right now?).
     * Returning false short-circuits the download with a friendly message.
     * Default = always available.
     */
    default boolean isAvailable(DownloadConfig config, Consumer<String> logSink) {
        return true;
    }

    /** Allow the strategy to forcibly stop any in-flight work (e.g. kill a subprocess). */
    default void abort() { /* no-op by default */ }
}
