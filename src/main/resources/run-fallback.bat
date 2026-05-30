@echo off
REM ════════════════════════════════════════════════════════════════
REM  Matthew Tube Downloader — Windows fallback launcher (v1.5)
REM
REM  If the bundled .exe refuses to start, drop this .bat next to
REM  matthew_tube_downloader-1.5.0.jar (from target\) and double-click.
REM  Requires a system Java 17+ on PATH.
REM ════════════════════════════════════════════════════════════════

setlocal

REM Force UTF-8 console so Persian / Arabic / CJK titles render correctly.
chcp 65001 >nul

set "JAR=%~dp0matthew_tube_downloader-1.5.0.jar"

if not exist "%JAR%" (
    echo Cannot find %JAR%.
    echo Place this .bat in the same folder as the JAR file, then re-run.
    pause
    exit /b 1
)

java ^
  --add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED ^
  --add-opens=javafx.base/com.sun.javafx.runtime=ALL-UNNAMED ^
  --enable-native-access=javafx.graphics ^
  --sun-misc-unsafe-memory-access=allow ^
  -Dfile.encoding=UTF-8 ^
  -Dsun.jnu.encoding=UTF-8 ^
  -Dstdout.encoding=UTF-8 ^
  -Dstderr.encoding=UTF-8 ^
  -Xmx768m ^
  -jar "%JAR%"

if errorlevel 1 (
    echo.
    echo The app exited with an error. Scroll up to see the stack trace.
    pause
)

endlocal
