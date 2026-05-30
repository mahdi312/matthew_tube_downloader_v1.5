#!/usr/bin/env bash
# fili – Bash version (2026)

set -euo pipefail

clear
cat << 'EOF'
======================================
      Matthew_Tube Downloader
======================================
EOF
echo

# ────────────────────────────────────────────────
# Logging
# ────────────────────────────────────────────────
LOG_DIR="$(dirname "$0")/logs"
mkdir -p "$LOG_DIR" 2>/dev/null || { echo "Cannot create logs dir"; exit 1; }

LOG_FILE="$LOG_DIR/log-$(date +%Y-%m-%d_%H-%M-%S).txt"
exec > >(tee -a "$LOG_FILE") 2>&1

echo "Log started: $(date '+%Y-%m-%d %H:%M:%S')"
echo "======================================"

# Check yt-dlp
command -v yt-dlp >/dev/null 2>&1 || { echo "yt-dlp not found. Install it first."; exit 1; }

# 1. URL
read -r -p "Enter YouTube URL: " URL
[ -z "$URL" ] && { echo "No URL provided."; exit 1; }
echo

# 2. Cookies
DEFAULT_COOKIES="$(pwd)/cookies.txt"
read -r -p "Cookies file (Enter = $DEFAULT_COOKIES): " COOKIES_PATH
COOKIES_PATH="${COOKIES_PATH:-$DEFAULT_COOKIES}"

COOKIES_ARG=""
[ -f "$COOKIES_PATH" ] && COOKIES_ARG="--cookies $COOKIES_PATH" && echo "Using cookies: $COOKIES_PATH" || echo "No cookies → public access only"
echo

# 3. Output directory
CURRENT_DIR="$(pwd)"
read -r -p "Output directory (Enter = $CURRENT_DIR): " OUTPUT_DIR
OUTPUT_DIR="${OUTPUT_DIR:-$CURRENT_DIR}"

[ ! -d "$OUTPUT_DIR" ] && mkdir -p "$OUTPUT_DIR" || { echo "Cannot create output dir"; exit 1; }
echo "Output: $OUTPUT_DIR"
echo

# 4. Download type
echo "1 = Video+Audio+Subs"
echo "2 = Audio only"
echo "3 = Subtitles only"
read -r -p "Choose: " DOWNLOAD_TYPE

case "$DOWNLOAD_TYPE" in
    1) MODE="video" ;;
    2) MODE="audio" ;;
    3) MODE="subs"  ;;
    *) echo "Invalid choice."; exit 1 ;;
esac
echo

# Detect playlist / single
PLAYLIST_TITLE=$(yt-dlp --flat-playlist --print "%(playlist_title)s" "$URL" 2>/dev/null | head -n 1)

if [ -n "$PLAYLIST_TITLE" ] && [ "$PLAYLIST_TITLE" != "NA" ]; then
    IS_PLAYLIST=1
    echo "Playlist: $PLAYLIST_TITLE"
else
    IS_PLAYLIST=0
    TITLE=$(yt-dlp --get-title "$URL" 2>/dev/null || echo "(title unavailable)")
    echo "Single video: $TITLE"
fi
echo

# Playlist selection
PLAYLIST_ARG=""
if [ "$IS_PLAYLIST" = 1 ]; then
    read -r -p "1 = All   2 = Select   : " SEL_CHOICE
    if [ "$SEL_CHOICE" = "2" ]; then
        echo "Showing first 30 items:"
        yt-dlp --flat-playlist --print "%(playlist_index)s  %(title)s" "$URL" | head -n 30
        read -r -p "Numbers/ranges (e.g. 1 3-7 12 or all): " SELECTION
        if [ "$SELECTION" != "all" ] && [ -n "$SELECTION" ]; then
            ITEMS=$(echo "$SELECTION" | tr -d ' ' | sed 's/,/ /g' | tr ' ' '\n' | \
                    while read -r t; do
                        if echo "$t" | grep -qE '^[0-9]+$'; then echo "$t"; fi
                        if echo "$t" | grep -qE '^([0-9]+)-([0-9]+)$'; then seq "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"; fi
                    done | sort -nu | paste -sd, -)
            PLAYLIST_ARG="--playlist-items $ITEMS"
        fi
    fi
fi
echo

# Safety flags
SAFETY_ARGS=(
    "--extractor-args" "youtubetab:skip=authcheck"
    "--sleep-requests" "1"
    "--playlist-end" "50"
    "--js-runtimes" "deno"
    "--remote-components" "ejs:github"
    "-i"
    "--sleep-interval" "12"
    "--max-sleep-interval" "30"
    "--retries" "20"
    "--fragment-retries" "10"
    "--socket-timeout" "90"
)

# Mode logic
case "$MODE" in
    subs)
        read -r -p "Languages (comma separated, e.g. en,fa or all): " SUB_LANGS
        [ -z "$SUB_LANGS" ] && SUB_LANGS="en"

        echo "1=srt 2=vtt"
        read -r -p "Choose: " SUB_FMT
        SUB_EXT=$([ "$SUB_FMT" = "2" ] && echo "vtt" || echo "srt")

        echo "1=all 2=manual 3=auto"
        read -r -p "Choose: " SUB_TYPE
        SUB_FLAGS=""
        [ "$SUB_TYPE" = "1" ] || [ "$SUB_TYPE" = "2" ] && SUB_FLAGS="$SUB_FLAGS --write-subs"
        [ "$SUB_TYPE" = "1" ] || [ "$SUB_TYPE" = "3" ] && SUB_FLAGS="$SUB_FLAGS --write-auto-subs"

        echo "Starting subtitles download..."
        yt-dlp --skip-download $SUB_FLAGS --sub-langs "$SUB_LANGS" --convert-subs "$SUB_EXT" \
               -o "$OUTPUT_DIR/%(playlist_index)s-%(title)s.%(ext)s" \
               $COOKIES_ARG $PLAYLIST_ARG "${SAFETY_ARGS[@]}" \
               "$URL"
        echo "Subtitles saved"
        ;;

    audio)
        echo "Audio formats:"
        yt-dlp -F "$URL" | grep audio | head -n 20
        read -r -p "Format code (Enter = bestaudio): " AUDIO_FMT
        [ -z "$AUDIO_FMT" ] && AUDIO_FMT="bestaudio"

        echo "Starting audio download..."
        yt-dlp -f "$AUDIO_FMT" --embed-metadata \
               -o "$OUTPUT_DIR/%(playlist_index)s-%(title)s.%(ext)s" \
               $COOKIES_ARG $PLAYLIST_ARG "${SAFETY_ARGS[@]}" \
               "$URL"
        echo "Audio saved"
        ;;

    video)
        echo "Video qualities:"
        yt-dlp -F "$URL" | grep -E '^[0-9]+.*video' | head -n 20
        echo "Presets: best / 1080 / 720 / 480 / 360"
        read -r -p "Choose: " VIDEO_Q

        case "$VIDEO_Q" in
            best)  FORMAT="bestvideo+bestaudio/best" ;;
            1080)  FORMAT="bestvideo[height<=1080]+bestaudio/best[height<=1080]" ;;
            720)   FORMAT="bestvideo[height<=?720]+bestaudio/best[height<=?720]" ;;
            480)   FORMAT="bestvideo[height<=?480]+bestaudio/best[height<=?480]" ;;
            360)   FORMAT="bestvideo[height<=?360]+bestaudio/best[height<=?360]" ;;
            *)     FORMAT="$VIDEO_Q" ;;
        esac

        read -r -p "Subtitles (en,fa,... none all): " SUB_LANGS_VIDEO
        SUB_ARG=""
        [ -n "$SUB_LANGS_VIDEO" ] && [ "$SUB_LANGS_VIDEO" != "none" ] && \
            SUB_ARG="--write-subs --write-auto-subs --sub-langs $SUB_LANGS_VIDEO --convert-subs srt --embed-subs"

        echo "Starting video download..."
        yt-dlp -f "$FORMAT" $SUB_ARG --embed-metadata \
               -o "$OUTPUT_DIR/%(playlist_index)s-%(title)s.%(ext)s" \
               $COOKIES_ARG $PLAYLIST_ARG "${SAFETY_ARGS[@]}" \
               "$URL"
        echo "Video saved"
        ;;
esac

echo "Finished."