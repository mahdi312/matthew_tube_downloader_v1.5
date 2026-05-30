# Matthew Tube Downloader — v1.5 Patch Notes

This release fixes **eight user-reported issues** and tightens a handful of
edge cases discovered during the audit.

---

## 🐛 Bug Fixes

### Fix 1 — "Detected single video: WARNING: No title found in player responses…"

**Symptom**: when YouTube ships a new player.js, recent yt-dlp builds emit
a warning to stderr while still extracting the title via a fallback path.
v1.4 was reading the *first* line of merged stdout/stderr, so the warning
text got promoted into the UI as if it were the video title.

**Fix** — `YtDlpService.getVideoTitle` and `detectPlaylistTitle` now:

  * pass `--no-warnings` so the noise never reaches the pipe,
  * scan output for the first non-`WARNING:` / non-`ERROR:` line, and
  * fall through with `"(title unavailable)"` if absolutely nothing usable
    came back.

Recommend running **Tools → Update yt-dlp** if you still see this — a
fresh yt-dlp build (`pip install -U yt-dlp` or `yt-dlp -U`) clears the
underlying root cause too.

### Fix 2 — v2rayN proxy port default

Default proxy port is now **10808** (v2rayN's default SOCKS5 inbound),
not the legacy 12334. A hint line next to the field reminds you that
Clash users want 7890. The "Use Proxy" checkbox stays **off** by default
so non-Iran users aren't surprised.

### Fix 3 — Persian / Farsi / Arabic titles came back as `???`

**Root cause** — every `ProcessBuilder` call read child-process output via
`new String(bytes)`, which uses the JVM's *platform default* charset.
On Windows that defaults to cp1252, so any UTF-8 byte sequence yt-dlp
emits (Persian, Arabic, CJK, emoji, …) was mojibake'd before it ever
reached the UI.

**Fix** — every single byte-to-string conversion in `YtDlpService` and
`HelloApplication` is now pinned to `StandardCharsets.UTF_8`, *and*
`-Dfile.encoding=UTF-8` / `-Dsun.jnu.encoding=UTF-8` /
`-Dstdout.encoding=UTF-8` / `-Dstderr.encoding=UTF-8` are passed to the
JVM by all three jpackage profiles (Windows / macOS / Linux). yt-dlp is
also invoked with `--encoding utf-8`.

After installing v1.5, Persian playlist titles render correctly:

```
Detected playlist: آموزش جاوا برای مبتدیان
Found 24 videos in playlist.
```

### Fix 4 — "Add to queue" from the Download tab

Two new buttons appear on the Download tab:

| Button | What it does |
|---|---|
| **➕ Add URL to queue** (next to the Analyze button) | Snapshots every current Download-tab setting (strategy, quality, proxy, output dir, subtitles, …) and enqueues the URL in the field. Works before or after Analyze. |
| **➕ Add selected to queue** (under the playlist table) | For playlists only: each ticked video becomes its own queue item, so you can mix-and-match playlist items with single videos on the queue. |

These buttons are wired through a new `MainController.setScheduler(...)`
hook called by `RootController.bootstrap` immediately after the
Download tab loads, so you don't have to open the Queue tab first.

### Fix 5 — Theme dropdown that actually works

The v1.4 "Accent color" ColorPicker was decorative — it never repainted
the UI. v1.5 ships a real theme switcher with **three** themes:

| Theme | Palette |
|---|---|
| **Dracula** (default) | Original deep navy + red accents |
| **Jungle** | Forest green palette, light leafy accents |
| **White Snow** | White + sky-blue, clean & airy |

Live preview: change the combo and the entire scene repaints in place.
Click Save to persist; click Reload to revert. Picked theme is stored
in `settings.json` as `themeName`.

Implementation — new `service/settings/ThemeManager` (weak-refs the
active `Scene`, swaps stylesheets atomically). The
`com/mst/matt/.../themes/` resource folder ships three CSS files;
adding a 4th is a one-line enum addition + new CSS.

### Fix 6 — Queue persistence across restarts

**Symptom** — if `enableSchedulerOnStart` was off, or if the scheduler
was started before the Queue tab was first opened, the queue list
"silently lost" itself on restart.

**Root cause** — `DownloadScheduler.start()` was the *only* code path
that called `QueuePersistence.load()`. So if the scheduler never
started, the in-memory list was empty even though `queue.json` was
intact on disk.

**Fix** — `DownloadScheduler` now hydrates `items` from
`QueuePersistence.load()` in its constructor, *independent of*
`start()`. Whether or not you have the scheduler thread running, your
queue is always restored at startup and stays restored until you
explicitly remove items. (Already-DONE items still appear; use
**🧹 Clear completed** when you want a clean slate.)

### Fix 7 — Windows .exe failed to launch (had to run the JAR manually)

Two changes were needed:

1. **Per-machine install** (`<winperuserinstall>false</winperuserinstall>`,
   was `true`). Per-user installs land under `%LOCALAPPDATA%` and the
   bundled JRE's launcher occasionally fails to resolve its own
   `runtime\bin\server\jvm.dll` due to path-length / permission quirks.
   Per-machine installs go under `Program Files\` instead, which is
   reliable.

2. **Console-attached launcher** (`<winconsole>true</winconsole>`). The
   launcher now opens a console window on start so any JVM stderr is
   visible. Once you've confirmed it launches, flip this back to
   `false` in `pom.xml` and rebuild for a "silent" production EXE.

3. **Fallback `.bat`** — a `run-fallback.bat` script is shipped under
   `src/main/resources/`. If the EXE ever fails again, drop the bat next
   to `target/matthew_tube_downloader-1.5.0.jar` and double-click — it
   invokes the JAR through your system `java.exe` with all the right
   `--add-opens` and UTF-8 flags.

### Fix 8 — YouTube vs generic-site URL separation

A live **site-type badge** sits next to the URL field:

  * `📍 YouTube` for `youtube.com`, `youtu.be`, `youtube-nocookie.com`
  * `🌐 Generic site` for everything else (Vimeo, Aparat, etc.)

Picking the **Invidious / Pure-Java** strategies with a non-YouTube URL
now auto-switches you back to **yt-dlp Direct** (the only strategy that
handles the 1000+ non-YT sites yt-dlp supports), with a log line so you
know what happened. This prevents the silent failure where users picked
Invidious for an Aparat URL and got a cryptic "instance lookup failed".

---

## 🆕 New helper — `YtDlpService.updateYtDlp(...)`

Convenience wrapper that runs `yt-dlp -U` first, then falls back to
`pip install -U yt-dlp`. The Tools tab can call this directly; it's also
the new recommended response when you see the "No title found in player
responses" warning in the log.

---

## 🔢 File-level summary

| File | Change |
|---|---|
| `service/YtDlpService.java` | UTF-8 everywhere, WARNING filtering, `--no-warnings`, `updateYtDlp()` helper, `--encoding utf-8` |
| `service/settings/AppSettings.java` | `themeName` field, default port → 10808 |
| `service/settings/ThemeManager.java` | **NEW** — live theme switching |
| `service/scheduler/DownloadScheduler.java` | Load queue in constructor (fixes lost-queue bug) |
| `controller/SettingsController.java` | Theme combo + live preview, Color-picker removed |
| `controller/RootController.java` | Wires scheduler → MainController for Add-to-queue buttons |
| `MainController.java` | Live site badge, port-10808 default, ➕ Add-to-queue handlers |
| `HelloApplication.java` | Forces UTF-8 system properties, ThemeManager.register |
| `main-view.fxml` | Site badge, ➕ buttons, port hint |
| `settings-view.fxml` | Theme combo replaces ColorPicker |
| `root-view.fxml` | Version label v1.5 |
| `themes/styles-dracula.css` | Original theme (renamed) + ComboBox/TabPane tints |
| `themes/styles-jungle.css` | **NEW** — forest green theme |
| `themes/styles-snow.css` | **NEW** — white + sky-blue theme |
| `run-fallback.bat` | **NEW** — manual EXE escape hatch |
| `pom.xml` | Version 1.5.0, UTF-8 javaoptions on all 3 jpackage profiles, per-machine install, console launcher |

---

## 🚀 How to test

1. **Persian title** — paste a Persian YouTube URL like
   `https://www.youtube.com/watch?v=<id-of-Farsi-video>`, click Analyze.
   The "Detected single video:" log line should show the Persian
   characters cleanly, not `???` or mojibake.

2. **v2rayN port** — open Settings → Proxy defaults: port should read
   `10808`. Toggle "Use Proxy" on the Download tab and verify the
   pre-filled value.

3. **Add to queue** — paste a URL, click **➕ Add URL to queue**, then
   open the Queue tab. The item should already be there.
   For a playlist URL: click Analyze → select 3 videos → click
   **➕ Add selected to queue** → Queue tab should show 3 new items.

4. **Themes** — Settings → Theme → switch to Jungle: the entire window
   should repaint immediately to green. Click Save, close the app, relaunch:
   it should come back up in Jungle.

5. **Queue persistence** — Settings → uncheck "Start scheduler on app
   launch", Save, restart. Open the Queue tab: your queued items should
   still be there (this was the v1.4 regression).

6. **EXE launch** — build with `mvn -P windows clean package`. Run the
   installer from `target/installer/MatthewTubeDownloader-1.5.0.exe`.
   The app should now start with no extra steps. If it ever fails again,
   run `run-fallback.bat` from the `target/` directory.

7. **Site badge** — type `https://aparat.com/v/xyz` into the URL field
   *without clicking Analyze*: the label next to the URL section should
   instantly switch to `🌐 Generic site`. If your strategy was Invidious,
   it will auto-flip to yt-dlp Direct with a log message.

---

## 🛣️ Known limitations / next steps

* The site badge currently classifies into just two buckets (YouTube vs
  generic). A future v1.6 could add per-site icons (Vimeo, Aparat,
  Instagram, …) reading from a small registry table.
* `winconsole=true` keeps a console window open. Flip to `false` in
  `pom.xml` once you've confirmed the EXE works for you.
* Jungle / Snow themes are tuned for `Light text on dark` (Jungle) and
  `Dark text on light` (Snow). The Quality Picker dialog inherits the
  active theme via `scene.getStylesheets()` (v1.5 fix).
