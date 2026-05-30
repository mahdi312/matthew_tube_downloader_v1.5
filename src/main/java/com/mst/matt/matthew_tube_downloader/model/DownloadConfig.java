package com.mst.matt.matthew_tube_downloader.model;

import com.mst.matt.matthew_tube_downloader.service.strategy.StrategyType;

import java.util.List;

/**
 * Holds all user-selected download configuration.
 *
 * NOTE (v1.2): Added strategy-related fields at the bottom — all existing
 * getters/setters above are untouched, so the rest of the codebase keeps working.
 */
public class DownloadConfig {

    public enum DownloadType { VIDEO, AUDIO, SUBTITLES }
    public enum VideoQuality { BEST, Q1080, Q720, Q480, Q360 }
    public enum SubFormat { SRT, VTT }
    public enum SubType { ALL, MANUAL, AUTO }

    private String url;
    private String outputDir;
    private String cookiesPath;
    private DownloadType downloadType = DownloadType.VIDEO;
    private VideoQuality videoQuality = VideoQuality.BEST;
    private String subtitleLanguages = "en";
    private SubFormat subFormat = SubFormat.SRT;
    private SubType subType = SubType.ALL;
    private boolean embedSubtitles = true;

    // Proxy settings
    private boolean useProxy = false;
    private String proxyHost = "";
    private String proxyPort = "";

    // Playlist selection
    private boolean isPlaylist;
    private boolean downloadAll = true;
    private List<Integer> selectedIndices;  // null = all

    // ────────────────────────────────────────────────
    //  v1.2 — Strategy fields (additive)
    // ────────────────────────────────────────────────
    private StrategyType strategy = StrategyType.YT_DLP_DIRECT;

    /** Strategy 1: Invidious/Piped instance base URL (e.g. https://yewtu.be). */
    private String invidiousInstance = "";
    /** Strategy 1: auto-rotate through public instances if the chosen one fails. */
    private boolean invidiousAutoRotate = true;

    /** Strategy 4: GitHub repo "owner/name" of the user's aio-downloader fork. */
    private String githubRepo = "";
    /** Strategy 4: workflow filename (e.g. "download.yml"). */
    private String githubWorkflow = "download.yml";
    /** Strategy 4: GitHub branch the workflow lives on. */
    private String githubBranch = "main";
    /** Strategy 4: GitHub personal access token (PAT) with `actions:write`. */
    private String githubToken = "";

    /** v1.3 - explicit yt-dlp format id chosen by the Quality Picker (overrides quality enum if set). */
    private String pickedFormatId = "";

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public String getCookiesPath() { return cookiesPath; }
    public void setCookiesPath(String cookiesPath) { this.cookiesPath = cookiesPath; }

    public DownloadType getDownloadType() { return downloadType; }
    public void setDownloadType(DownloadType downloadType) { this.downloadType = downloadType; }

    public VideoQuality getVideoQuality() { return videoQuality; }
    public void setVideoQuality(VideoQuality videoQuality) { this.videoQuality = videoQuality; }

    public String getSubtitleLanguages() { return subtitleLanguages; }
    public void setSubtitleLanguages(String subtitleLanguages) { this.subtitleLanguages = subtitleLanguages; }

    public SubFormat getSubFormat() { return subFormat; }
    public void setSubFormat(SubFormat subFormat) { this.subFormat = subFormat; }

    public SubType getSubType() { return subType; }
    public void setSubType(SubType subType) { this.subType = subType; }

    public boolean isEmbedSubtitles() { return embedSubtitles; }
    public void setEmbedSubtitles(boolean embedSubtitles) { this.embedSubtitles = embedSubtitles; }

    public boolean isUseProxy() { return useProxy; }
    public void setUseProxy(boolean useProxy) { this.useProxy = useProxy; }

    public String getProxyHost() { return proxyHost; }
    public void setProxyHost(String proxyHost) { this.proxyHost = proxyHost; }

    public String getProxyPort() { return proxyPort; }
    public void setProxyPort(String proxyPort) { this.proxyPort = proxyPort; }

    public boolean isPlaylist() { return isPlaylist; }
    public void setPlaylist(boolean playlist) { isPlaylist = playlist; }

    public boolean isDownloadAll() { return downloadAll; }
    public void setDownloadAll(boolean downloadAll) { this.downloadAll = downloadAll; }

    public List<Integer> getSelectedIndices() { return selectedIndices; }
    public void setSelectedIndices(List<Integer> selectedIndices) { this.selectedIndices = selectedIndices; }

    // ── v1.2 getters/setters ──
    public StrategyType getStrategy() { return strategy; }
    public void setStrategy(StrategyType strategy) {
        this.strategy = (strategy != null) ? strategy : StrategyType.YT_DLP_DIRECT;
    }

    public String getInvidiousInstance() { return invidiousInstance; }
    public void setInvidiousInstance(String invidiousInstance) { this.invidiousInstance = invidiousInstance; }

    public boolean isInvidiousAutoRotate() { return invidiousAutoRotate; }
    public void setInvidiousAutoRotate(boolean invidiousAutoRotate) { this.invidiousAutoRotate = invidiousAutoRotate; }

    public String getGithubRepo() { return githubRepo; }
    public void setGithubRepo(String githubRepo) { this.githubRepo = githubRepo; }

    public String getGithubWorkflow() { return githubWorkflow; }
    public void setGithubWorkflow(String githubWorkflow) { this.githubWorkflow = githubWorkflow; }

    public String getGithubBranch() { return githubBranch; }
    public void setGithubBranch(String githubBranch) { this.githubBranch = githubBranch; }

    public String getGithubToken() { return githubToken; }
    public void setGithubToken(String githubToken) { this.githubToken = githubToken; }

    public String getPickedFormatId() { return pickedFormatId; }
    public void setPickedFormatId(String pickedFormatId) { this.pickedFormatId = pickedFormatId; }

    /**
     * Get the proxy URL string for yt-dlp (e.g. "socks5://127.0.0.1:12334").
     */
    public String getProxyUrl() {
        if (!useProxy || proxyHost == null || proxyHost.isBlank() || proxyPort == null || proxyPort.isBlank()) {
            return null;
        }
        return "socks5://" + proxyHost.trim() + ":" + proxyPort.trim();
    }

    /**
     * Build the yt-dlp format string based on quality selection.
     * v1.3 - if the user picked an explicit yt-dlp format id via the Quality Picker,
     * that wins over the quality preset.
     */
    public String getFormatString() {
        if (pickedFormatId != null && !pickedFormatId.isBlank()) {
            return pickedFormatId;
        }
        return switch (videoQuality) {
            case BEST -> "bestvideo+bestaudio/best";
            case Q1080 -> "bestvideo[height<=1080]+bestaudio/best[height<=1080]";
            case Q720 -> "bestvideo[height<=?720]+bestaudio/best[height<=?720]";
            case Q480 -> "bestvideo[height<=?480]+bestaudio/best[height<=?480]";
            case Q360 -> "bestvideo[height<=?360]+bestaudio/best[height<=?360]";
        };
    }

    /**
     * Build playlist-items argument value (e.g. "1,3,5,7-10").
     */
    public String getPlaylistItemsArg() {
        if (downloadAll || selectedIndices == null || selectedIndices.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selectedIndices.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(selectedIndices.get(i));
        }
        return sb.toString();
    }

    /** Convenience: target height in pixels for the selected quality (0 = best). */
    public int getTargetHeight() {
        return switch (videoQuality) {
            case BEST -> 0;
            case Q1080 -> 1080;
            case Q720 -> 720;
            case Q480 -> 480;
            case Q360 -> 360;
        };
    }
}
