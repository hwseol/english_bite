"""One-off: rerun every already-cached video through the current pipeline (word-level
caption timing, translation, idiom extraction), overwriting its cache entry. Needed after
a pipeline change that affects data already on disk - e.g. adding real per-word timestamps.
"""
import glob
import json
import time
from pathlib import Path

from pipeline import process_video

CACHE_DIR = Path(__file__).parent / "cache"

video_ids = [Path(p).stem for p in sorted(glob.glob(str(CACHE_DIR / "*.json")))]
print(f"{len(video_ids)} cached video(s) to reprocess", flush=True)

for video_id in video_ids:
    url = f"https://www.youtube.com/watch?v={video_id}"
    print(f"[{video_id}] reprocessing...", flush=True)
    t0 = time.time()
    try:
        result = process_video(url)
    except Exception as e:
        print(f"[FAILED] {video_id}: {e}", flush=True)
        continue
    (CACHE_DIR / f"{video_id}.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    has_words = bool(result["sentences"] and result["sentences"][0].get("words"))
    print(
        f"[done] {video_id}: {result['sentence_count']} sentences, "
        f"{len(result['idioms'])} idiom(s), words={has_words} in {time.time() - t0:.1f}s",
        flush=True,
    )
