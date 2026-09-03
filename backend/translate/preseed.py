"""Pre-process a list of YouTube URLs into the cache ahead of time, so real
users always get an instant (already-translated) response instead of waiting
for on-demand ingestion.

Usage:
    python preseed.py <url1> <url2> ...
    python preseed.py --file urls.txt   (one URL per line)
"""
import json
import sys
import time
from pathlib import Path

from pipeline import extract_video_id, process_video

CACHE_DIR = Path(__file__).parent / "cache"
CACHE_DIR.mkdir(exist_ok=True)


def preseed(urls: list[str]):
    for url in urls:
        video_id = extract_video_id(url)
        cache_path = CACHE_DIR / f"{video_id}.json"
        if cache_path.exists():
            print(f"[skip] {video_id} already cached")
            continue

        print(f"[processing] {video_id} ({url})", flush=True)
        t0 = time.time()
        try:
            result = process_video(url)
        except Exception as e:
            print(f"[FAILED] {video_id}: {e}")
            continue

        cache_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[done] {video_id}: {result['sentence_count']} sentences in {time.time() - t0:.1f}s")


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(1)

    if args[0] == "--file":
        urls = [line.strip() for line in Path(args[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
    else:
        urls = args

    preseed(urls)
