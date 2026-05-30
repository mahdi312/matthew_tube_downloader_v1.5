package com.mst.matt.matthew_tube_downloader.service.strategy;

/**
 * One of the 5 download strategies described in DirectLink.md.
 *
 *  1. YT_DLP_DIRECT   — original behavior; call yt-dlp directly (default).
 *  2. INVIDIOUS       — resolve direct googlevideo.com URL via Invidious/Piped, then HTTP GET.
 *  3. PURE_JAVA       — sealedtx/java-youtube-downloader (no yt-dlp binary needed).
 *  4. YT_DLP_PROXY    — wrap yt-dlp through yt-dlp-proxy (auto-rotating free proxies).
 *  5. GITHUB_ACTIONS  — offload to GitHub Actions runners and pull the artifact ZIP.
 *  6. SNI_INFO        — show install instructions for a system-level DPI/SNI bypass tool.
 */
public enum StrategyType {
    YT_DLP_DIRECT  ("yt-dlp (Direct)",        "Default. Calls yt-dlp directly. Best when network is healthy."),
    INVIDIOUS      ("Invidious / Piped API",  "Resolves direct googlevideo.com URLs via a public Invidious instance — best for SNI-filtered Iran connections."),
    PURE_JAVA      ("Pure Java (sealedtx)",   "Embedded Java YouTube parser; no yt-dlp binary required. Supports HTTP/SOCKS proxy."),
    YT_DLP_PROXY   ("yt-dlp-proxy (wrapper)", "Runs yt-dlp through auto-rotating free public proxies. Requires yt-dlp-proxy installed on PATH."),
    GITHUB_ACTIONS ("GitHub Actions",         "Triggers a workflow on your fork of an aio-downloader repo, then downloads the artifact ZIP."),
    SNI_INFO       ("SNI/DPI Bypass (info)",  "System-level helper (not a download method) — shows install instructions for Waujito/youtubeUnblock.");

    private final String displayName;
    private final String description;

    StrategyType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    @Override
    public String toString() { return displayName; }
}
