"""Runs on the local PC (residential IP, not blocked by YouTube's bot detection): scans the same
channels catalog.py always has, downloads the audio for each new short-enough video, transcribes
it with Whisper right here, gets the idioms and Korean subtitles from a language model (through
the local FreeLLMAPI router, see llm_config.json / llm_enrich.py), and publishes only the finished
result to the server's /admin/publish endpoint. The server no longer runs any models - it just
stores and serves what this sends, which is what lets it be a tiny, cheap instance.

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

import llm_client
import llm_enrich
import words_cache
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


def _ensure_router() -> None:
    """Idioms and subtitles come from a language model reached through the local router. If the
    router is down (or its key file is missing) a run would produce nothing usable, so stop the
    whole run instead of publishing videos without idioms/translations."""
    ok, why = llm_client.router_alive(llm_client.load_config())
    if not ok:
        raise SystemExit(f"LLM 라우터를 쓸 수 없어서 이번 동기화를 중단합니다 ({why}). 관용어·번역 없는 결과를 올리지 않기 위해서예요.")


def _process_and_publish(token: str, item: dict, audio_path: Path) -> None:
    # Imported here, not at the top: pulling in whisper takes many seconds and a few hundred MB
    # of RAM, wasted on the (most common) runs where nothing is new.
    from pipeline import process_uploaded_audio

    result = process_uploaded_audio(item["video_id"], audio_path, title=item.get("title"))
    resp = requests.post(
        f"{SERVER_URL}/admin/publish",
        headers={"X-Admin-Token": token},
        json={"result": result, "catalog_entry": item},
        timeout=120,
    )
    resp.raise_for_status()


# How far back a run looks for videos the server doesn't have yet. It used to be "since local
# midnight", which dropped everything uploaded between the last run of the day and midnight (and
# anything missed while the computer was off). 48 hours is generous on purpose: videos the server
# already has are skipped with one cheap request, so a wide window costs little.
SCAN_WINDOW_HOURS = 48

# If the language models fail this many videos in a row, something is wrong with all of them
# (quota used up everywhere, no network): stop the run rather than download audio for nothing.
MAX_CONSECUTIVE_LLM_FAILURES = 2


def sync() -> None:
    token = _load_admin_token()
    cutoff = time.time() - SCAN_WINDOW_HOURS * 3600
    words_cache.prune()
    router_checked = False
    llm_failures = 0

    for channel_name, url in CHANNELS.items():
        print(f"[{channel_name}] checking latest {RECENT_CHECK_COUNT} uploads...", flush=True)
        try:
            video_ids = fetch_recent_video_ids(url, RECENT_CHECK_COUNT)
        except Exception as e:
            # One channel's listing failing (YouTube hiccup, "Failed to resolve url") must not end the
            # whole run: the channels after it would go unchecked until the next one.
            print(f"  [FAILED listing] {channel_name}: {e}", flush=True)
            continue
        for video_id in video_ids:
            # Cheapest check first: one request to the server, before any yt-dlp call.
            if _already_known(video_id):
                print(f"  [skip] {video_id} already on server")
                continue

            try:
                info = fetch_video_details(video_id)
            except Exception as e:
                print(f"  [skip] {video_id}: {e}")
                continue

            timestamp = info.get("timestamp") or 0
            if timestamp < cutoff:
                print(f"  ...older than {SCAN_WINDOW_HOURS}h, stopping this channel")
                break
            duration = info.get("duration") or 0
            if duration > MAX_DURATION_SECONDS:
                print(f"  [skip] {video_id} too long ({duration}s): {info.get('title')}")
                continue

            print(f"  [processing] {video_id}: {info.get('title')}", flush=True)
            audio_path = None
            if words_cache.load(video_id) is not None:
                # Deferred earlier because no model could finish it: the transcript is still on disk.
                print("    (받아쓰기 결과가 남아 있어서 다운로드와 Whisper를 건너뜁니다)", flush=True)
            else:
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
                if not router_checked:
                    _ensure_router()
                    router_checked = True
                _process_and_publish(token, item, audio_path)
                print(f"  [published] {video_id}")
                llm_failures = 0
            except llm_enrich.AllModelsFailed as e:
                # Not published, not retried here: the server still doesn't have it, so the next
                # run (3 hours later) starts it over from the beginning.
                llm_failures += 1
                print(f"  [DEFERRED - 모델 실패] {video_id}: {e}")
                if llm_failures >= MAX_CONSECUTIVE_LLM_FAILURES:
                    if audio_path:
                        shutil.rmtree(audio_path.parent, ignore_errors=True)
                    raise SystemExit(f"연속 {llm_failures}개 영상에서 모든 모델이 실패해서 이번 동기화를 중단합니다.")
            except Exception as e:
                print(f"  [FAILED process/publish] {video_id}: {e}")
            finally:
                if audio_path:
                    shutil.rmtree(audio_path.parent, ignore_errors=True)


if __name__ == "__main__":
    sync()
