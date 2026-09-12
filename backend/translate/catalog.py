"""Collect short-form CNN/BBC/Bloomberg uploads into a catalog the app can browse, instead of
requiring a pasted YouTube URL. No YouTube Data API key needed - yt-dlp reads the same public
metadata (title, view count, duration, upload date) an official API key would, just via a
different (widely-used, actively maintained) library.

Each run only scans each channel's last 24h of uploads (see WINDOW_SECONDS) to keep the scan
fast, but that's just how far back a single run looks for *new* videos - results accumulate
into catalog.json across runs (this is scheduled every few hours), so the catalog itself holds
everything ever collected, not only what's within the window right now.

Usage:
    python catalog.py            # collect + merge into catalog.json
    python catalog.py --preseed  # also run each newly-found video through the translation pipeline
"""
import json
import sys
import time
from pathlib import Path

from yt_dlp import YoutubeDL

CHANNELS = {
    "CNN": "https://www.youtube.com/@CNN/videos",
    "BBC News": "https://www.youtube.com/@BBCNews/videos",
    "Bloomberg": "https://www.youtube.com/@markets/videos",
    "The Economist": "https://www.youtube.com/@TheEconomist/videos",
    "Fox Business": "https://www.youtube.com/@FoxBusiness/videos",
}

MAX_DURATION_SECONDS = 20 * 60  # longer videos are skipped rather than downloaded and cut,
                                  # consistent with never storing/re-encoding video ourselves
RECENT_CHECK_COUNT = 40  # how many of each channel's newest uploads to inspect per run -
                          # CNN/BBC post many times a day, 15 was missing same-day videos
WINDOW_SECONDS = 24 * 60 * 60  # a rolling 24h window by absolute timestamp, not a calendar-date
                                 # string match - upload_date's timezone vs. the server's local
                                 # date was silently dropping videos right at the day boundary

CATALOG_PATH = Path(__file__).parent / "catalog.json"

# Ordered checked in this sequence, first match wins - a title mentioning both a politician and
# a stock ticker is far more likely a politics story with a market angle than the reverse, so
# politics/society go before economy, and sports (rarely ambiguous with the others) last.
CATEGORY_KEYWORDS: dict[str, tuple[str, ...]] = {
    "정치": (
        "trump", "biden", "president", "senate", "congress", "election", "midterm",
        "republican", "democrat", "gop", "white house", "governor", "campaign",
        "vote", "policy", "administration", "impeach", "capitol", "prime minister",
        "parliament", "government", "diplomat", "sanctions", "war", "military",
        "ukraine", "gaza", "israel", "iran", "nato",
    ),
    "경제": (
        "stock", "market", "economy", "economic", "inflation", "fed", "interest rate",
        "gdp", "earnings", "ipo", "nasdaq", "dow", "s&p", "bond", "yield", "trade deal",
        "tariff", "recession", "jobs report", "unemployment", "business", "ceo",
        "billion", "investment", "crypto", "bitcoin", "oil price", "housing market",
    ),
    "스포츠": (
        "nfl", "nba", "mlb", "nhl", "soccer", "football", "basketball", "baseball",
        "olympic", "world cup", "championship", "tournament", "coach", "athlete",
        "match", "score", "playoff", "tennis", "golf",
    ),
}


def classify_category(title: str) -> str:
    """A quick keyword heuristic, not a model call - this runs once per video on the PC as
    part of the free scan/download pass, and adding an LLM round-trip here would mean either
    slowing that down or adding yet more load to the AWS server's already-the-bottleneck single
    Ollama worker (see project memory on the processing queue). Good enough to sort a news feed
    into rough sections; not aiming for perfect precision."""
    lowered = title.lower()
    for category, keywords in CATEGORY_KEYWORDS.items():
        if any(keyword in lowered for keyword in keywords):
            return category
    return "사회"


def upsert_catalog_entry(item: dict) -> None:
    """Merge one video's metadata into catalog.json, keyed by video_id. Used both by this
    script's own yt-dlp-based discovery and by server.py's /admin/ingest, which receives
    metadata the Android app already scraped on-device instead."""
    existing = json.loads(CATALOG_PATH.read_text(encoding="utf-8")) if CATALOG_PATH.exists() else []
    by_id = {v["video_id"]: v for v in existing}
    by_id[item["video_id"]] = item
    items = sorted(by_id.values(), key=lambda v: v["view_count"], reverse=True)
    CATALOG_PATH.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")


def fetch_recent_video_ids(channel_url: str, count: int) -> list[str]:
    with YoutubeDL({"extract_flat": "in_playlist", "playlistend": count, "quiet": True, "no_warnings": True}) as ydl:
        info = ydl.extract_info(channel_url, download=False)
    return [e["id"] for e in info.get("entries", []) if e.get("id")]


def fetch_video_details(video_id: str) -> dict:
    with YoutubeDL({"quiet": True, "no_warnings": True}) as ydl:
        return ydl.extract_info(f"https://www.youtube.com/watch?v={video_id}", download=False)


def collect_today() -> list[dict]:
    cutoff = time.time() - WINDOW_SECONDS
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

            timestamp = info.get("timestamp") or 0
            if timestamp < cutoff:
                # Uploads list is newest-first, so nothing after this is in the window either.
                print(f"  ...older than 24h, stopping this channel")
                break
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
                "timestamp": timestamp,
            })
            print(f"  [today] {video_id} ({duration}s, {info.get('view_count')} views): {info.get('title')}")

    results.sort(key=lambda v: v["view_count"], reverse=True)
    return results


if __name__ == "__main__":
    new_items = collect_today()

    existing = json.loads(CATALOG_PATH.read_text(encoding="utf-8")) if CATALOG_PATH.exists() else []
    by_id = {item["video_id"]: item for item in existing}
    for item in new_items:
        by_id[item["video_id"]] = item  # refresh view_count etc. if seen again within the window

    items = sorted(by_id.values(), key=lambda v: v["view_count"], reverse=True)
    CATALOG_PATH.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n{len(new_items)} new video(s) this run, {len(items)} total in catalog -> {CATALOG_PATH}")

    if "--preseed" in sys.argv:
        # Every catalog entry without a cache file yet, not just this run's new discoveries -
        # preseed.py already skips ones that are already cached, so this is cheap, and it's
        # what keeps a video from getting silently stuck forever if a run somehow adds it to
        # catalog.json without also preseeding it (e.g. this script run without --preseed).
        from preseed import preseed
        urls = [f"https://www.youtube.com/watch?v={v['video_id']}" for v in items]
        preseed(urls)
