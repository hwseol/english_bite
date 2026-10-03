"""Runs on the local PC (residential IP, not blocked by YouTube's bot detection): scans the same
channels catalog.py always has, downloads the audio for each new short-enough video, runs the
whole Whisper/NLLB/Ollama pipeline right here, and publishes only the finished result to the
server's /admin/publish endpoint. The server no longer runs any models - it just stores and
serves what this sends, which is what lets it be a tiny, cheap instance.

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
import subprocess
import sys
import tempfile
import time
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

SERVER_URL = "https://184-193-203-68.sslip.io"
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


def _ensure_ollama() -> None:
    """Idiom extraction calls a local Ollama server, and idioms.py quietly skips a chunk it
    can't get an answer for - so a run started with Ollama down would publish videos with no
    idioms, permanently (the result gets cached). Make sure it's actually answering first, and
    stop the whole run rather than publish degraded results if it won't start."""
    def up() -> bool:
        try:
            return requests.get("http://127.0.0.1:11434/api/tags", timeout=3).status_code == 200
        except requests.RequestException:
            return False

    if up():
        return
    print("Ollama가 꺼져 있어서 시작합니다...", flush=True)
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    subprocess.Popen(["ollama", "serve"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=flags)
    for _ in range(30):
        time.sleep(2)
        if up():
            return
    raise SystemExit("Ollama를 시작하지 못해서 이번 동기화를 중단합니다 (관용구 없는 결과를 저장하지 않기 위해).")


def _process_and_publish(token: str, item: dict, audio_path: Path) -> None:
    # Imported here, not at the top: pulling in torch/whisper/transformers takes many seconds
    # and a few GB of RAM, wasted on the (most common) runs where nothing is new.
    from pipeline import process_uploaded_audio

    result = process_uploaded_audio(item["video_id"], audio_path)
    resp = requests.post(
        f"{SERVER_URL}/admin/publish",
        headers={"X-Admin-Token": token},
        json={"result": result, "catalog_entry": item},
        timeout=120,
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
    ollama_checked = False

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
                if not ollama_checked:
                    _ensure_ollama()
                    ollama_checked = True
                _process_and_publish(token, item, audio_path)
                print(f"  [published] {video_id}")
            except Exception as e:
                print(f"  [FAILED process/publish] {video_id}: {e}")
            finally:
                shutil.rmtree(audio_path.parent, ignore_errors=True)


if __name__ == "__main__":
    sync()
