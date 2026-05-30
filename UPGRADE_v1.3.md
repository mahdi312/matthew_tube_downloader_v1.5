# Matthew Tube Downloader — v1.3 Upgrade Notes

This release adds **6 major features** on top of v1.2's 5 download strategies. The
UI is now organized as a 4-tab interface, and everything you had before
(Download tab) keeps working exactly as it did.

---

## 🎯 What's New

| # | Feature | Where to find it |
|---|---------|-------------------|
| **1** | **Dependency manager** — Detects `yt-dlp`, `yt-dlp-proxy`, `ffmpeg`, `python`, `pip`. Compares yt-dlp against the latest GitHub release. Installs/updates with one-click confirmation. | **Tools** tab |
| **2** | **Generic webpage extractor** — Non-YouTube URLs are first tried through yt-dlp (handles 1000+ sites), then fall back to HTML scraping (`<video>` tags, `og:video` meta, `.m3u8/.mp4` URLs). | Auto on **Download** tab when URL isn't YouTube |
| **3** | **Quality picker** — For non-YouTube sites, a modal table shows every detected format (ID, resolution, codec, container, bitrate, size). User picks one. | Pops up automatically after Analyze on non-YouTube URLs |
| **4** | **Scheduled queue + pause/resume** — Add URLs to a persisted queue. Run immediately, manually, or at a scheduled date/time. Pause/resume **really works** for yt-dlp & yt-dlp-proxy (via `--continue`); other strategies show a warning before cancelling. | **Queue** tab |
| **5** | **Queue management** — Multi-select, run-all, pause/resume/remove selected, clear completed. Each item locks in its own DownloadConfig snapshot. | **Queue** tab (bulk action buttons) |
| **6** | **Settings panel** — Toggle proxy section, scheduler-on-start, yt-dlp update check. Defaults for strategy, output dir, quality, subtitles, proxy, accent color. Persisted to JSON in user config dir. | **Settings** tab |

---

## 🏗️ New Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     HelloApplication (start)                    │
│                              │                                  │
│                              ▼                                  │
│                       RootController                            │
│                       (TabPane host)                            │
│                              │                                  │
│         ┌────────┬───────────┼────────────┬───────────┐         │
│         ▼        ▼           ▼            ▼           ▼         │
│      Download  Queue       Tools       Settings    Status bar   │
│       (Main)   (Queue)    (Tools)     (Settings)  (yt-dlp ⚠)    │
│         │        │           │            │                     │
│         │        │           │            │                     │
│         └────────┴───────────┴────────────┘                     │
│           shared services:                                      │
│             YtDlpService       — yt-dlp wrapper                 │
│             DownloadStrategy   — 5 strategies (v1.2)            │
│             DependencyManager  — tools detection / install      │
│             WebpageExtractor   — generic extractor (yt-dlp+HTML)│
│             DownloadScheduler  — queue + pause/resume engine    │
│             SettingsManager    — JSON-persisted settings        │
└─────────────────────────────────────────────────────────────────┘
```

### File layout (new in v1.3)

```
src/main/java/com/mst/matt/matthew_tube_downloader/
├── controller/                          ← NEW PACKAGE
│   ├── RootController.java              ← TabPane host
│   ├── QueueController.java             ← Queue tab
│   ├── ToolsController.java             ← Tools tab
│   ├── SettingsController.java          ← Settings tab
│   └── QualityPickerController.java     ← Modal dialog
├── service/
│   ├── dependency/                      ← NEW PACKAGE
│   │   ├── DependencyManager.java
│   │   └── ToolStatus.java
│   ├── extractor/                       ← NEW PACKAGE
│   │   ├── WebpageExtractor.java
│   │   └── FormatInfo.java
│   ├── scheduler/                       ← NEW PACKAGE
│   │   ├── DownloadScheduler.java
│   │   ├── QueueItem.java
│   │   └── QueuePersistence.java
│   └── settings/                        ← NEW PACKAGE
│       ├── AppSettings.java
│       └── SettingsManager.java
└── (existing v1.2 code unchanged)

src/main/resources/com/mst/matt/matthew_tube_downloader/
├── root-view.fxml                       ← NEW: TabPane root
├── main-view.fxml                       ← unchanged (Download tab)
├── queue-view.fxml                      ← NEW
├── tools-view.fxml                      ← NEW
├── settings-view.fxml                   ← NEW
├── quality-picker.fxml                  ← NEW
└── styles.css                           ← unchanged
```

### Persistence locations

| File          | Linux                                       | macOS                                                          | Windows                            |
|---------------|---------------------------------------------|----------------------------------------------------------------|------------------------------------|
| settings.json | `~/.matthew_tube_downloader/settings.json`  | `~/Library/Application Support/MatthewTubeDownloader/settings.json` | `%APPDATA%\MatthewTubeDownloader\settings.json` |
| queue.json    | same dir as above                           | same dir as above                                              | same dir as above                  |

---

## 🛠️ Building

### Fat JAR (cross-platform)

```bash
mvn clean package
java -jar target/matthew_tube_downloader-1.3.0.jar
```

### Native installers (jpackage)

The pom now has 3 profiles that auto-activate based on the build host's OS:

| Target  | Command (any platform)        | Output                                |
|---------|--------------------------------|---------------------------------------|
| Windows | `mvn -P windows clean package` | `target/installer/MatthewTubeDownloader-1.3.0.exe` |
| macOS   | `mvn -P mac clean package`     | `target/installer/MatthewTubeDownloader-1.3.0.dmg` |
| Linux   | `mvn -P linux clean package`   | `target/installer/matthew-tube-downloader_1.3.0_amd64.deb` |

> **Note:** Each profile only works **on the matching host OS** because the
> `jpackage` tool can only produce installers for its own platform.
> So to build a Windows EXE, you must run the command on a Windows machine.

#### Adding an installer icon (optional)

Drop a square PNG icon at any of these paths (each is OS-specific) and uncomment
the matching `<icon>` line in `pom.xml`:

| Platform | Path                                                                  | Format             |
|----------|------------------------------------------------------------------------|--------------------|
| Windows  | `src/main/resources/com/mst/matt/matthew_tube_downloader/icon.ico`     | `.ico`, 256×256    |
| macOS    | `src/main/resources/com/mst/matt/matthew_tube_downloader/icon.icns`    | `.icns`            |
| Linux    | `src/main/resources/com/mst/matt/matthew_tube_downloader/icon.png`     | `.png`, 256×256    |

#### Windows installer details (jpackage)

The Windows profile is configured for a polished desktop install experience:

- **Per-user install** (`winperuserinstall=true`) — no admin rights needed.
- **Directory chooser** (`windirchooser=true`) — user picks install location.
- **Start Menu group** "Downloader" + shortcut (with prompt).
- **Desktop shortcut option** (`winshortcutprompt=true`).
- **Upgrade UUID** (`winupgradeuuid`) — stable across releases so users get
  in-place upgrades when they run a newer installer.

---

## 🧪 How to test each feature

### Feature 1 — Tools tab
Open the **Tools** tab. The 5 tools will refresh automatically. Click an Update/Install button — a confirm dialog appears, then the action streams to the log.

### Feature 2 + 3 — Generic extractor
Paste a Vimeo / Twitter / news-site URL into the Download tab. Click Analyze. After analysis, a "Quality Picker" dialog opens listing every detected format. Pick one — the chosen format-id is wired into yt-dlp's `-f` flag automatically.

### Feature 4 + 5 — Queue
1. Open the **Queue** tab.
2. Paste a URL, choose "Schedule at:", pick a date+time 2 min in the future.
3. Click "Add to queue".
4. Wait — the scheduler thread ticks every 15s and starts due items.
5. Or click ▶ Run all now to start immediately.
6. Click ⏸ Pause selected on a running yt-dlp item — process is killed, `.part` file remains. Click ▶ Resume → yt-dlp picks up via `--continue`.

### Feature 6 — Settings
1. Open the **Settings** tab.
2. Toggle "Show proxy section on Download tab" → click Save → switch to Download tab → proxy section hides/shows accordingly.
3. Change default strategy / quality / output dir → click Save → next time you open the app, those are the defaults.

---

## 🔒 Security & privacy notes

- The **GitHub PAT** (Strategy 4) is intentionally **never** persisted to disk.
  You enter it each session on the Download tab.
- The Tools tab will **never** run install commands without an explicit
  confirmation dialog.
- ZIP extraction in the GitHub Actions strategy enforces path normalization
  (zip-slip protection).
- HTTPS-only when fetching the Invidious instance list and GitHub API.
