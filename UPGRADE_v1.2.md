# Matthew Tube Downloader — v1.2 Upgrade Notes

This upgrade adds **5 download strategies** from `DirectLink.md` while keeping the previous
yt-dlp-direct behavior intact as the default. Nothing in your existing usage breaks.

---

## 🎯 What's New

A new **"Download Strategy"** section at the top of the UI lets you pick:

| # | Strategy | Description |
|---|----------|-------------|
| 1 | **yt-dlp (Direct)** ⬅ default | Original behavior — unchanged. |
| 2 | **Invidious / Piped API** | Resolves direct `googlevideo.com` URLs via a public Invidious instance, downloads with `HttpClient`. **Best for Iran** — bypasses YouTube SNI filtering. |
| 3 | **Pure Java (sealedtx)** | Embedded Java YouTube parser; no `yt-dlp` needed. Honors SOCKS5 proxy. |
| 4 | **yt-dlp-proxy (wrapper)** | Runs yt-dlp through auto-rotating free public proxies. Needs `yt-dlp-proxy` on PATH. |
| 5 | **GitHub Actions** | Triggers a workflow on your fork of an aio-downloader repo, polls until it finishes, downloads the artifact ZIP. Works during severe Iran filtering. |
| ★ | **SNI/DPI Bypass (info)** | Not a download method — just shows install instructions for [Waujito/youtubeUnblock](https://github.com/Waujito/youtubeUnblock). |

The existing **SOCKS5 proxy field** still works and is honored by:
- yt-dlp (Direct)
- yt-dlp-proxy (passed alongside its own proxy rotation)
- Pure Java (sealedtx) — applied via `Config.proxy(host, port)`
- Invidious — used when streaming the final googlevideo.com URL

---

## 📁 Files Changed / Added

### Modified (5 files)
- `pom.xml` — added JitPack repo + optional `sealedtx/java-youtube-downloader` dep
- `src/main/java/module-info.java` — added `requires java.net.http;`
- `src/main/java/com/mst/matt/matthew_tube_downloader/model/DownloadConfig.java` — added strategy fields (additive)
- `src/main/java/com/mst/matt/matthew_tube_downloader/MainController.java` — wired UI to strategy selection
- `src/main/java/com/mst/matt/matthew_tube_downloader/service/DownloadTask.java` — now dispatches to chosen strategy
- `src/main/resources/com/mst/matt/matthew_tube_downloader/main-view.fxml` — added "Download Strategy" section

### New `service/strategy/` package (7 files)
- `StrategyType.java` — enum of strategies + descriptions
- `DownloadStrategy.java` — interface
- `YtDlpDirectStrategy.java` — wraps the unchanged `YtDlpService` (default)
- `InvidiousStrategy.java` — Strategy 1
- `PureJavaStrategy.java` — Strategy 2 (loaded via reflection)
- `YtDlpProxyStrategy.java` — Strategy 3
- `GitHubActionsStrategy.java` — Strategy 4

### Untouched
- `Launcher.java`, `HelloApplication.java`
- `service/YtDlpService.java` — completely unchanged
- `model/VideoInfo.java` — unchanged
- `resources/.../styles.css` — unchanged
- `resources/matthew_tube_downloader.sh` — unchanged

---

## 🚀 Build & Run

```bash
mvn clean package           # builds the fat JAR
mvn javafx:run              # runs the UI
```

Maven will resolve the new dep from JitPack on first build:

```xml
<dependency>
  <groupId>com.github.sealedtx</groupId>
  <artifactId>java-youtube-downloader</artifactId>
  <version>3.2.5</version>
  <optional>true</optional>
</dependency>
```

If JitPack is unreachable, you can simply **delete** that one `<dependency>` block from
`pom.xml` — the **Pure Java** strategy will then show a friendly "library not on classpath"
message at runtime, and every other strategy keeps working.

---

## 🧪 How to Test Each Strategy

### 1. yt-dlp (Direct) — default
Nothing to configure. Paste a URL → Analyze → Start Download.
Should behave **identically** to the previous version.

### 2. Invidious / Piped
- Select "Invidious / Piped API" in the strategy dropdown.
- Leave the instance field blank → auto-picks healthy instances from `api.invidious.io/instances.json`.
- Or type an explicit instance like `https://yewtu.be`.
- Click Analyze → Start Download.
- Logs will show `[Invidious] trying instance: …` and `[Invidious] picked: 720p (mp4)`.

### 3. Pure Java (sealedtx)
- Select "Pure Java (sealedtx)".
- If you haven't run `mvn package` yet, the strategy will log a friendly message
  asking you to add the dep (already present in pom.xml).
- Otherwise: Analyze → Start Download. Logs show `[PureJava] …`.

### 4. yt-dlp-proxy
- Install once on your machine:
  ```
  pip install yt-dlp-proxy
  yt-dlp-proxy update
  ```
- Select "yt-dlp-proxy (wrapper)" in the dropdown.
- Same UI flow as yt-dlp Direct. Logs show `yt-dlp-proxy …` instead of `yt-dlp …`.

### 5. GitHub Actions
- Fork an aio-downloader-style repo (e.g. `ProAlit/aio-downloader` or
  `alitavakoli01/YouTubeDownloader`) onto your own GitHub account.
- Create a Personal Access Token at https://github.com/settings/tokens
  with scopes: `actions:write`, `contents:read`.
- Select "GitHub Actions" in the dropdown, then fill in:
  - **Repo**: `yourname/aio-downloader`
  - **Workflow**: `download.yml` (or whatever your fork uses)
  - **Branch**: `main`
  - **PAT**: paste the token
- Paste a YouTube URL → Start Download.
- App will trigger the workflow, poll until done, and extract the artifact ZIP
  into your output folder.

### 6. SNI/DPI Bypass (info)
- Just shows install instructions for `Waujito/youtubeUnblock`.
- This is a router/Linux-level tool — not a Java-integrable download method.
- After installing it on your system, switch back to **yt-dlp (Direct)**.

---

## 🛠️ Architecture

```
                     ┌───────────────────────┐
                     │  MainController (UI)  │
                     └──────────┬────────────┘
                                │ builds DownloadConfig
                                ▼
                     ┌───────────────────────┐
                     │      DownloadTask     │
                     │   (JavaFX Task)       │
                     └──────────┬────────────┘
                                │ resolveStrategy()
                                ▼
              ┌─────────────────┴──────────────────┐
              │     DownloadStrategy (interface)   │
              └─────────────────┬──────────────────┘
                                │
      ┌──────────┬───────────┬─┴────────┬────────────────┐
      ▼          ▼           ▼          ▼                ▼
 YtDlpDirect  Invidious  PureJava   YtDlpProxy    GitHubActions
   uses        uses        uses        uses           uses
 YtDlpService  HttpClient  reflection  YtDlpService   HttpClient
                + Gson      + sealedtx                  + Gson
```

The existing `YtDlpService` class is **never modified** — both `YtDlpDirectStrategy`
and `YtDlpProxyStrategy` reuse its `buildCommand()` / `executeCommand()` methods.

---

## 🔒 Security Notes

- The GitHub PAT is held only in memory (PasswordField in the UI) and sent over
  HTTPS to `api.github.com`. It is **not** written to disk by this app.
- The Invidious strategy follows redirects via Java's `HttpClient` with
  `Redirect.ALWAYS`. The final hostname is whatever the instance returns
  (typically `*.googlevideo.com`).
- ZIP extraction in `GitHubActionsStrategy` normalizes paths and refuses
  entries that escape the output directory (zip-slip protection).
