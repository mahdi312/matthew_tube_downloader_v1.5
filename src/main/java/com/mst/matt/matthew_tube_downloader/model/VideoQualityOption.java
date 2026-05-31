package com.mst.matt.matthew_tube_downloader.model;

/**
 * One selectable video quality row built from yt-dlp format metadata.
 *
 * @param label      UI label, e.g. {@code "2160p (4K) — mp4"}
 * @param formatSpec yt-dlp {@code -f} selector passed via {@link DownloadConfig#setPickedFormatId}
 * @param height     Target height in pixels ({@code 0} = best / unknown)
 */
public record VideoQualityOption(String label, String formatSpec, int height, String formatId) {

    /** Fallback when yt-dlp cannot list formats. */
    public static VideoQualityOption bestFallback() {
        return new VideoQualityOption("Best available",
                "bestvideo+bestaudio/bestvideo+bestaudio/best", 0, "");
    }

    @Override
    public String toString() { return label; }
}
