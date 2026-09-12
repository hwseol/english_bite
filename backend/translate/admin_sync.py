"""Runs on the local PC (residential IP, not blocked by YouTube's bot detection) and hands off
to the AWS server (blocked) for everything else: scans the same channels catalog.py always has,
downloads just the audio for each new short-enough video, and uploads it to the server's
/admin/ingest endpoint, which transcribes/translates/extracts idioms and folds the result into
its own catalog.json.

This replaces catalog.py --preseed as the thing that actually keeps the catalog fresh - catalog.py
is still useful standalone (e.g. for local dev against a local server), but on this machine the
scheduled task should point at this script instead.

Usage:
    python admin_sync.py

Requires ~/.secrets/englishbite_admin_token.txt to hold the token configured on the server via
the ADMIN_TOKEN env var (see /etc/systemd/system/englishbite-api.service on the EC2 instance).
"""
import datetime
import shutil
import sys
import tempfile
from pathlib import Path

import requests
import yt_dlp

# Windows' console defaults to the system codepage (e.g. cp949 on a Korean Windows install),
# which can't encode a lot of ordinary Unicode punctuation (curly quotes, em dashes) that
# regularly shows up in video titles - crashing print() partway through a run. UTF-8
# unconditionally, with a replacement fallback for anything even UTF-8 output can't help with
# (a broken pipe, redirected-to-something-weirder-than-a-terminal case).
if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from catalog import CHANNELS, MAX_DURATION_SECONDS, RECENT_CHECK_COUNT
from catalog import classify_category, fetch_recent_video_ids, fetch_video_details

SERVER_URL = "https://13-218-170-114.sslip.io"
TOKEN_PATH = Path.home() / ".secrets" / "englishbite_admin_token.txt"


def _load_admin_token() -> str:
    if not TOKEN_PATH.exists():
        raise SystemExit(
            f"관리자 토큰 파일이 없어요: {TOKEN_PATH}\n"
            "서버의 ADMIN_TOKEN 값을 이 파일에 한 줄로 저장해주세요."
        )
    return TOKEN_PATH.read_text(encoding="utf-8").strip()


def _already_known(video_id: str) -> bool:
    """True if the server already has this video cached or in progress - checked via the
    same GET the app itself polls, so we don't waste bandwidth re-downloading/re-uploading
    audio for a video that's already done or already being processed."""
    try:
        resp = requests.get(f"{SERVER_URL}/videos/{video_id}", timeout=10)
        return resp.status_code == 200
    except requests.RequestException:
        return False  # can't tell - safer to try the upload than to silently skip it


def _download_audio(video_id: str) -> Path:
    tmp_dir = Path(tempfile.mkdtemp(prefix="ebite_admin_sync_"))
    opts = {
        "format": "bestaudio/best",
        "outtmpl": str(tmp_dir / "audio.%(ext)s"),
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        ydl.download([f"https://www.youtube.com/watch?v={video_id}"])
    files = list(tmp_dir.glob("audio.*"))
    if not files:
        shutil.rmtree(tmp_dir, ignore_errors=True)
        raise RuntimeError("오디오 다운로드 결과 파일을 찾을 수 없어요")
    return files[0]


def _upload(token: str, item: dict, audio_path: Path) -> None:
    with audio_path.open("rb") as f:
        resp = requests.post(
            f"{SERVER_URL}/admin/ingest",
            headers={"X-Admin-Token": token},
            data={
                "video_id": item["video_id"],
                "title": item["title"],
                "channel": item["channel"],
                "thumbnail": item.get("thumbnail") or "",
                "view_count": item["view_count"],
                "duration": item["duration"],
                "upload_date": item.get("upload_date") or "",
                "timestamp": item["timestamp"],
                "category": item["category"],
            },
            files={"audio": (audio_path.name, f)},
            timeout=300,
        )
    resp.raise_for_status()


def sync() -> None:
    token = _load_admin_token()
    # The server can only process about one video every ~15-20 minutes on its current CPU-only
    # instance (Whisper + NLLB + Ollama, all CPU-bound, one at a time - see
    # _job_queue in server.py) - catalog.py's rolling 24h window was piling up 30+ videos per
    # run, which the server then took most of a day to work through. Scoping this to just
    # today (local midnight) keeps each run's batch small enough to actually catch up.
    midnight = datetime.datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    cutoff = midnight.timestamp()

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
                print("  ...older than 24h, stopping this channel")
                break
            duration = info.get("duration") or 0
            if duration > MAX_DURATION_SECONDS:
                print(f"  [skip] {video_id} too long ({duration}s): {info.get('title')}")
                continue

            if _already_known(video_id):
                print(f"  [skip] {video_id} already on server: {info.get('title')}")
                continue

            print(f"  [processing] {video_id}: {info.get('title')}", flush=True)
            try:
                audio_path = _download_audio(video_id)
            except Exception as e:
                print(f"  [FAILED download] {video_id}: {e}")
                continue

            thumbnails = info.get("thumbnails") or []
            title = info.get("title") or ""
            item = {
                "video_id": video_id,
                "title": title,
                "channel": channel_name,
                "thumbnail": thumbnails[-1]["url"] if thumbnails else None,
                "view_count": info.get("view_count") or 0,
                "duration": duration,
                "upload_date": info.get("upload_date"),
                "timestamp": timestamp,
                "category": classify_category(title),
            }
            try:
                _upload(token, item, audio_path)
                print(f"  [uploaded] {video_id} - server will transcribe/translate")
            except Exception as e:
                print(f"  [FAILED upload] {video_id}: {e}")
            finally:
                shutil.rmtree(audio_path.parent, ignore_errors=True)


if __name__ == "__main__":
    sync()
