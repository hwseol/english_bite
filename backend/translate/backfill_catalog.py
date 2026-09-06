"""One-off: catalog.json used to be overwritten each run with only the last 24h of uploads,
so every video from before the accumulating-catalog fix (see catalog.py) that's now older
than 24h fell out of it, even though it's still fully cached and playable. Re-fetch metadata
for every cache/*.json entry missing from catalog.json and merge it back in.
"""
import glob
import json
from pathlib import Path

from catalog import fetch_video_details, CATALOG_PATH

CACHE_DIR = Path(__file__).parent / "cache"


def guess_channel(info: dict) -> str:
    name = (info.get("channel") or info.get("uploader") or "").lower()
    if "bbc" in name:
        return "BBC News"
    if "bloomberg" in name:
        return "Bloomberg"
    return "CNN"


existing = json.loads(CATALOG_PATH.read_text(encoding="utf-8")) if CATALOG_PATH.exists() else []
by_id = {item["video_id"]: item for item in existing}

cached_ids = [Path(p).stem for p in sorted(glob.glob(str(CACHE_DIR / "*.json")))]
missing = [vid for vid in cached_ids if vid not in by_id]
print(f"{len(cached_ids)} cached video(s), {len(missing)} missing from catalog.json", flush=True)

for video_id in missing:
    try:
        info = fetch_video_details(video_id)
    except Exception as e:
        print(f"[skip] {video_id}: {e}", flush=True)
        continue

    thumbnails = info.get("thumbnails") or []
    by_id[video_id] = {
        "video_id": video_id,
        "title": info.get("title"),
        "channel": guess_channel(info),
        "thumbnail": thumbnails[-1]["url"] if thumbnails else None,
        "view_count": info.get("view_count") or 0,
        "duration": info.get("duration") or 0,
        "upload_date": info.get("upload_date"),
        "timestamp": info.get("timestamp") or 0,
    }
    print(f"[added] {video_id} ({by_id[video_id]['channel']}): {info.get('title')}", flush=True)

items = sorted(by_id.values(), key=lambda v: v["view_count"], reverse=True)
CATALOG_PATH.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"\n{len(items)} total in catalog -> {CATALOG_PATH}")
