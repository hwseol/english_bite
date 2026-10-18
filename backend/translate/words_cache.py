"""Keeps a video's Whisper transcript (word timings) on disk while it waits for the language models.

Whisper is the slow step (and the one a weak laptop feels most). If every model is unavailable when
a video's transcript is ready - a daily quota used up, the router down - the video is deferred to the
next run. Without this, that run would download the audio and transcribe it all over again; with it,
the retry goes straight to the language-model step. The entry is deleted as soon as the video has
been processed successfully, and anything older than a week is pruned.
"""

import json
import time
from pathlib import Path

WORDS_DIR = Path(__file__).parent / "words_cache"
MAX_AGE_SECONDS = 7 * 24 * 3600


def _path(video_id: str) -> Path:
    # Video ids are 11 URL-safe characters; anything else never becomes a file name.
    safe = "".join(c for c in video_id if c.isalnum() or c in "_-")
    return WORDS_DIR / f"{safe}.json"


def load(video_id: str) -> list[dict] | None:
    p = _path(video_id)
    if not p.exists():
        return None
    try:
        words = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return words if isinstance(words, list) and words else None


def save(video_id: str, words: list[dict]) -> None:
    WORDS_DIR.mkdir(parents=True, exist_ok=True)
    _path(video_id).write_text(json.dumps(words, ensure_ascii=False), encoding="utf-8")


def delete(video_id: str) -> None:
    _path(video_id).unlink(missing_ok=True)


def prune() -> None:
    if not WORDS_DIR.exists():
        return
    cutoff = time.time() - MAX_AGE_SECONDS
    for p in WORDS_DIR.glob("*.json"):
        try:
            if p.stat().st_mtime < cutoff:
                p.unlink()
        except OSError:
            pass
