"""Collect today's short-form CNN/BBC uploads into a catalog the app can browse, instead of
requiring a pasted YouTube URL. No YouTube Data API key needed - yt-dlp reads the same public
metadata (title, view count, duration, upload date) an official API key would, just via a
different (widely-used, actively maintained) library.

Usage:
    python catalog.py            # collect + write catalog.json
    python catalog.py --preseed  # also run each new video through the translation pipeline
"""
import json
import sys
from datetime import date
from pathlib import Path

from yt_dlp import YoutubeDL

CHANNELS = {
    "CNN": "https://www.youtube.com/@CNN/videos",
    "BBC News": "https://www.youtube.com/@BBCNews/videos",
}

MAX_DURATION_SECONDS = 20 * 60  # longer videos are skipped rather than downloaded and cut,
                                  # consistent with never storing/re-encoding video ourselves
RECENT_CHECK_COUNT = 15  # how many of each channel's newest uploads to inspect per run

CATALOG_PATH = Path(__file__).parent / "catalog.json"


def fetch_recent_video_ids(channel_url: str, count: int) -> list[str]:
    with YoutubeDL({"extract_flat": "in_playlist", "playlistend": count, "quiet": True}) as ydl:
        info = ydl.extract_info(channel_url, download=False)
    return [e["id"] for e in info.get("entries", []) if e.get("id")]


def fetch_video_details(video_id: str) -> dict:
    with YoutubeDL({"quiet": True}) as ydl:
        return ydl.extract_info(f"https://www.youtube.com/watch?v={video_id}", download=False)


def collect_today() -> list[dict]:
    today = date.today().strftime("%Y%m%d")
    results = []
    for channel_name, url in CHANNELS.items():
        print(f"[{channel_name}] checking latest {RECENT_CHECK_COUNT} uploads...", flush=True)
        video_ids = fetch_recent_video_ids(url, RECENT_CHECK_COUNT)
        for video_id in video_ids:
            try:
                info = fetch_video_details(video_id)
            except Exception as e:
                print(f"  [skip] {video_id}: {e}")
                continue

            if info.get("upload_date") != today:
                continue
            duration = info.get("duration") or 0
            if duration > MAX_DURATION_SECONDS:
                print(f"  [skip] {video_id} too long ({duration}s): {info.get('title')}")
                continue

            thumbnails = info.get("thumbnails") or []
            results.append({
                "video_id": video_id,
                "title": info.get("title"),
                "channel": channel_name,
                "thumbnail": thumbnails[-1]["url"] if thumbnails else None,
                "view_count": info.get("view_count") or 0,
                "duration": duration,
                "upload_date": info.get("upload_date"),
            })
            print(f"  [today] {video_id} ({duration}s, {info.get('view_count')} views): {info.get('title')}")

    results.sort(key=lambda v: v["view_count"], reverse=True)
    return results


if __name__ == "__main__":
    items = collect_today()
    CATALOG_PATH.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n{len(items)} video(s) uploaded today -> {CATALOG_PATH}")

    if "--preseed" in sys.argv:
        from preseed import preseed
        urls = [f"https://www.youtube.com/watch?v={v['video_id']}" for v in items]
        preseed(urls)
