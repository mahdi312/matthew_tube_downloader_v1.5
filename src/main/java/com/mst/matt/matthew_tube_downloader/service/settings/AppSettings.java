package com.mst.matt.matthew_tube_downloader.service.settings;

import com.mst.matt.matthew_tube_downloader.model.DownloadConfig;
import com.mst.matt.matthew_tube_downloader.service.strategy.StrategyType;

/**
 * Global user preferences, persisted as JSON to {@link SettingsManager#settingsFile()}.
 *
 * Plain POJO (no JavaFX properties) so Gson can ser/de it directly. The UI binds
 * controls to fields via the SettingsController.
 *
 * v1.5 additions:
 *   • {@link #themeName} — picks one of the 3 stylesheets (Dracula/Jungle/Snow).
 *   • Default proxy port now 10808 (v2rayN's default SOCKS5 inbound).
 */
public class AppSettings {

    // ── Feature toggles (the "enable/disable" parts requested) ──
    public boolean enableProxySection      = true;   // show the proxy section in Download tab
    public boolean enableSchedulerOnStart  = true;   // start the scheduler thread automatically
    public boolean autoCheckYtDlpUpdates   = true;   // run a yt-dlp version check on startup
    public boolean rememberWindowSize      = true;   // restore window dimensions

    /** v1.4 — maximum simultaneous downloads run by the queue scheduler. 1–8 makes sense. */
    public int maxConcurrentDownloads      = 2;

    // ── Default values applied to new Download Configs ──
    public StrategyType defaultStrategy = StrategyType.YT_DLP_DIRECT;
    public String defaultOutputDir = System.getProperty("user.home") + "/Downloads";
    public DownloadConfig.DownloadType   defaultDownloadType = DownloadConfig.DownloadType.VIDEO;
    public DownloadConfig.VideoQuality   defaultQuality      = DownloadConfig.VideoQuality.BEST;
    public DownloadConfig.SubFormat      defaultSubFormat    = DownloadConfig.SubFormat.SRT;
    public DownloadConfig.SubType        defaultSubType      = DownloadConfig.SubType.ALL;
    public String  defaultSubtitleLangs = "en";
    public boolean defaultEmbedSubs     = true;

    // ── Proxy defaults ──
    // v1.5: default port changed to 10808 (v2rayN's default SOCKS5 inbound).
    public boolean defaultUseProxy = false;
    public String  defaultProxyHost = "127.0.0.1";
    public String  defaultProxyPort = "10808";

    /** Netscape-format cookies.txt for yt-dlp (analyze + download when the file exists). */
    public String defaultCookiesPath = "";

    /** YouTube PO Token via bgutil HTTP server — off by default; enable in Settings when server runs. */
    public boolean usePotProvider = false;
    public String potProviderHttpUrl = "http://127.0.0.1:4416";

    // ── Strategy-specific defaults ──
    public String  invidiousDefaultInstance = "";
    public boolean invidiousDefaultAutoRotate = true;
    public String  githubDefaultRepo = "";
    public String  githubDefaultWorkflow = "download.yml";
    public String  githubDefaultBranch = "main";
    // GitHub PAT is NEVER persisted to disk on purpose. User enters it per session.

    // ── Queue / Scheduler schedule-window defaults ──
    /**
     * Default queue start-time in "HH:mm" format (24-hour).
     * When a URL is added to the queue from the Download tab (or from Queue tab
     * with "Schedule at" mode), the scheduled start time of that item defaults to
     * today at this hour:minute if the configured time is still in the future,
     * otherwise tomorrow at this hour:minute.
     *
     * Empty string / null  = use "immediate" (current time) as the default.
     */
    public String queueDefaultStartTime = "";

    /**
     * Default queue end-time in "HH:mm" format (24-hour).
     * Purely informational / UI hint for now — the scheduler will not start NEW
     * items whose scheduledAt is past the window end for the day.
     * Empty string / null  = no end-time restriction.
     */
    public String queueDefaultEndTime = "";

    /**
     * When true, items added to the queue from the Download tab are scheduled
     * using {@link #queueDefaultStartTime} instead of being set to "immediate".
     * When false (or queueDefaultStartTime is blank) items start immediately.
     */
    public boolean queueUseScheduleWindow = false;

    // ── Theme (v1.5: real theme switching, not just an accent color) ──
    /** "DRACULA", "JUNGLE", or "SNOW". See {@link ThemeManager.Theme}. */
    public String themeName = "DRACULA";
    /** Kept for v1.4 backward-compat — not actually applied anywhere now that themes exist. */
    public String accentColor = "#4caf50";

    // ── Last-known window geometry ──
    public double windowWidth = 1100;
    public double windowHeight = 800;

    /** Defensive copy helper — used when handing a snapshot to a queue item. */
    public AppSettings copy() {
        AppSettings c = new AppSettings();
        c.enableProxySection = enableProxySection;
        c.enableSchedulerOnStart = enableSchedulerOnStart;
        c.autoCheckYtDlpUpdates = autoCheckYtDlpUpdates;
        c.rememberWindowSize = rememberWindowSize;
        c.defaultStrategy = defaultStrategy;
        c.defaultOutputDir = defaultOutputDir;
        c.defaultDownloadType = defaultDownloadType;
        c.defaultQuality = defaultQuality;
        c.defaultSubFormat = defaultSubFormat;
        c.defaultSubType = defaultSubType;
        c.defaultSubtitleLangs = defaultSubtitleLangs;
        c.defaultEmbedSubs = defaultEmbedSubs;
        c.defaultUseProxy = defaultUseProxy;
        c.defaultProxyHost = defaultProxyHost;
        c.defaultProxyPort = defaultProxyPort;
        c.defaultCookiesPath = defaultCookiesPath;
        c.usePotProvider = usePotProvider;
        c.potProviderHttpUrl = potProviderHttpUrl;
        c.invidiousDefaultInstance = invidiousDefaultInstance;
        c.invidiousDefaultAutoRotate = invidiousDefaultAutoRotate;
        c.githubDefaultRepo = githubDefaultRepo;
        c.githubDefaultWorkflow = githubDefaultWorkflow;
        c.githubDefaultBranch = githubDefaultBranch;
        c.accentColor = accentColor;
        c.themeName = themeName;
        c.windowWidth = windowWidth;
        c.windowHeight = windowHeight;
        c.maxConcurrentDownloads = maxConcurrentDownloads;
        c.queueDefaultStartTime = queueDefaultStartTime;
        c.queueDefaultEndTime = queueDefaultEndTime;
        c.queueUseScheduleWindow = queueUseScheduleWindow;
        return c;
    }
}
