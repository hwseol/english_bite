"""Turn one long YouTube lecture into several short catalog entries ("parts") the app can study
one sitting at a time, e.g. a 1h49m CS50 lecture -> ten ~10 minute parts.

    python series_sync.py WbzNRTTrX0g --lecture 0 --title Search
    python series_sync.py WbzNRTTrX0g --lecture 0 --title Search --server https://dev.184-193-203-68.sslip.io

How it works
  1. Download the lecture's audio once and transcribe the WHOLE thing with Whisper. Every word then
     carries an absolute timestamp in the original video, so a part needs no offset arithmetic -
     the app just plays the real YouTube video from the part's start to its end.
  2. Cut the sentence list into parts at sentence ends, about --part-minutes long each (a short
     leftover tail is merged into the previous part).
  3. Run each part through the normal pipeline (idioms + Korean translation) and save it under
     series_work/<video id>/ - so processing never has to be repeated.
  4. Publish every saved part to the server (/admin/publish). The entry id is "<video id>-pNN";
     the entry also records the real youtube_id and the start/end second of the slice.

Re-running is cheap: finished steps are reused (--force redoes processing/publishing). Use
--process-only / --publish-only to split the steps, e.g. process once, then publish the same
parts to the dev server for testing and to production afterwards.

Admin token: EB_ADMIN_TOKEN in the environment (use this for the dev server, which has its own
token), otherwise the same ~/.secrets/englishbite_admin_token.txt the regular sync uses.
"""

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

import requests
import yt_dlp

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import admin_sync  # noqa: E402  (reuses its audio download, Ollama check and token loading)

WORK_ROOT = Path(__file__).parent / "series_work"
CATEGORY = "강의"


def fetch_info(video_id: str) -> dict:
    with yt_dlp.YoutubeDL({"quiet": True, "no_warnings": True, "skip_download": True}) as ydl:
        return ydl.extract_info(f"https://www.youtube.com/watch?v={video_id}", download=False)


def split_into_parts(sentences: list[dict], target_s: float, min_tail_s: float) -> list[list[dict]]:
    """Group consecutive sentences into parts of roughly target_s seconds, always cutting at a
    sentence end. A leftover tail shorter than min_tail_s joins the previous part instead of
    becoming a part of its own."""
    parts: list[list[dict]] = []
    current: list[dict] = []
    for s in sentences:
        current.append(s)
        if s["end"] - current[0]["start"] >= target_s:
            parts.append(current)
            current = []
    if current:
        tail = current[-1]["end"] - current[0]["start"]
        if parts and tail < min_tail_s:
            parts[-1].extend(current)
        else:
            parts.append(current)
    return parts


def _words_json(sentences: list[dict]) -> list[dict]:
    """Back to the shape pipeline._process_from_words expects (milliseconds)."""
    return [
        {"text": w["text"], "start_ms": w["start"] * 1000, "end_ms": w["end"] * 1000}
        for s in sentences for w in s["words"]
    ]


def process(args, info: dict, work: Path) -> list[Path]:
    # Heavy imports only here: torch/whisper/transformers take seconds and GBs of RAM.
    import pipeline

    n_words_path = work / "words.json"
    if n_words_path.exists() and not args.force:
        words = json.loads(n_words_path.read_text(encoding="utf-8"))
        print(f"받아쓰기 결과를 재사용합니다 ({len(words)}단어)", flush=True)
    else:
        print("오디오를 내려받는 중...", flush=True)
        audio = admin_sync._download_audio(args.video_id)
        try:
            print("강의 전체를 받아쓰는 중 (오래 걸려요)...", flush=True)
            words = pipeline._transcribe_audio(audio)
        finally:
            shutil.rmtree(audio.parent, ignore_errors=True)
        n_words_path.write_text(json.dumps(words, ensure_ascii=False), encoding="utf-8")
        print(f"받아쓰기 완료: {len(words)}단어", flush=True)

    sentences = pipeline.words_to_sentences(words)
    parts = split_into_parts(sentences, args.part_minutes * 60, args.min_tail_minutes * 60)
    total = len(parts)
    print(f"{len(sentences)}문장 -> {total}개 조각", flush=True)

    admin_sync._ensure_ollama()
    thumb = info.get("thumbnail") or f"https://i.ytimg.com/vi/{args.video_id}/hqdefault.jpg"
    saved = []
    for idx, part in enumerate(parts, start=1):
        out = work / f"part_{idx:02d}.json"
        saved.append(out)
        if out.exists() and not args.force:
            print(f"  [skip] {idx}/{total} 이미 처리됨", flush=True)
            continue
        entry_id = f"{args.video_id}-p{idx:02d}"
        start = max(0.0, part[0]["start"] - 0.5)
        end = part[-1]["end"] + 0.5
        result = pipeline._process_from_words(entry_id, _words_json(part))
        result.update({"youtube_id": args.video_id, "start_second": round(start, 2), "end_second": round(end, 2)})
        entry = {
            "video_id": entry_id,
            "title": f"{args.series_name} · {args.lecture}강 {args.title} ({idx}/{total})",
            "channel": args.series_name,
            "thumbnail": thumb,
            "view_count": 0,
            "duration": int(end - start),
            "upload_date": info.get("upload_date") or "",
            "timestamp": int(info.get("timestamp") or 0),
            "category": CATEGORY,
            "series": args.series_slug,
            "series_order": args.lecture * 1000 + idx,
            "part_index": idx,
            "part_count": total,
            "lecture": args.lecture,
            "youtube_id": args.video_id,
            "start_second": round(start, 2),
            "end_second": round(end, 2),
        }
        out.write_text(json.dumps({"result": result, "entry": entry}, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"  [processed] {idx}/{total} {int(start)}s-{int(end)}s ({result['sentence_count']}문장, "
              f"관용구 {len(result['idioms'])}개)", flush=True)
    return saved


def publish(args, files: list[Path]) -> None:
    token = os.environ.get("EB_ADMIN_TOKEN") or admin_sync._load_admin_token()
    server = args.server.rstrip("/")
    for f in files:
        data = json.loads(f.read_text(encoding="utf-8"))
        entry_id = data["entry"]["video_id"]
        if not args.force:
            try:
                if requests.get(f"{server}/videos/{entry_id}", timeout=15).status_code == 200:
                    print(f"  [skip] {entry_id} 서버에 이미 있음", flush=True)
                    continue
            except requests.RequestException:
                pass
        resp = requests.post(
            f"{server}/admin/publish",
            headers={"X-Admin-Token": token},
            json={"result": data["result"], "catalog_entry": data["entry"]},
            timeout=120,
        )
        resp.raise_for_status()
        print(f"  [published] {entry_id} -> {server}", flush=True)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("video_id", help="the lecture's 11-character YouTube id")
    ap.add_argument("--lecture", type=int, required=True, help="lecture number (used for ordering)")
    ap.add_argument("--title", required=True, help="lecture title, e.g. Search")
    ap.add_argument("--series-name", default="CS50 AI", help="shown as the channel / filter chip")
    ap.add_argument("--series-slug", default="cs50-ai")
    ap.add_argument("--part-minutes", type=float, default=10)
    ap.add_argument("--min-tail-minutes", type=float, default=3)
    ap.add_argument("--server", default=admin_sync.SERVER_URL)
    ap.add_argument("--process-only", action="store_true")
    ap.add_argument("--publish-only", action="store_true")
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    work = WORK_ROOT / args.video_id
    work.mkdir(parents=True, exist_ok=True)

    if args.publish_only:
        files = sorted(work.glob("part_*.json"))
        if not files:
            print("저장된 조각이 없어요. 먼저 처리부터 하세요.")
            return 1
    else:
        info = fetch_info(args.video_id)
        files = process(args, info, work)
    if not args.process_only:
        publish(args, files)
    print("완료", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
