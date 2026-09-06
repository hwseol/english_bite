"""One-off: add the "idioms" field to cache files written before idioms.py existed,
reusing already-translated sentences instead of reprocessing the whole video.
"""
import glob
import json

from idioms import extract_idioms

for path in sorted(glob.glob("cache/*.json")):
    data = json.load(open(path, encoding="utf-8"))
    if "idioms" in data:
        print(f"[skip] {data['video_id']} already has idioms")
        continue

    print(f"[{data['video_id']}] {data['sentence_count']} sentences", flush=True)
    data["idioms"] = extract_idioms(data["sentences"])
    print(f"  -> {len(data['idioms'])} idiom(s)", flush=True)

    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
