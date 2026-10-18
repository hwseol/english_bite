"""Speech to text through Groq's Whisper API (replaces the local faster-whisper model, which ran on the
PC's CPU for ~3 minutes per video).

One call turns a downloaded audio file into the word list the rest of the pipeline expects:
    [{"text": "We", "start_ms": 20.0, "end_ms": 200.0}, ...]
Groq's whisper-large-v3-turbo returns per-word timestamps that match the old local model to within
~0.05 s (checked on a real CNN clip: same 115 words, same pacing).

Failure kinds, like llm_client:
    SttUnavailable - quota used up / provider down for longer than we are willing to wait. The caller
                     defers the video (nothing is published, the next run starts it over).
    SttRejected    - the provider refused this one file; a per-video failure, the run goes on.
    SttNoSpeech    - no words recognised (the old "no speech" case).

The key comes from a file named in llm_config.json ("stt" section), never from the repository.
"""

import shutil
import subprocess
import tempfile
import time
from pathlib import Path

import requests

import llm_client

GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
MAX_UPLOAD_BYTES = 24 * 1024 * 1024  # Groq's free tier accepts 25 MB per file

DEFAULTS = {
    "model": "whisper-large-v3-turbo",
    "key_file": "~/.secrets/groq_api_key.txt",
    "max_wait_on_429_s": 600,
    "retries": 3,
    "backoff_seconds": [5, 20, 60],
    "timeout_s": 300,
}


class SttUnavailable(Exception):
    pass


class SttNoSpeech(Exception):
    pass


class SttRejected(Exception):
    """The provider refused this particular file (HTTP 400/413/...): retrying will not help, but it
    says nothing about the service as a whole, so it is a per-video failure, not a reason to stop."""


def _settings(config: dict | None) -> dict:
    cfg = dict(DEFAULTS)
    cfg.update((config or llm_client.load_config()).get("stt", {}))
    return cfg


def _compress(audio_path: Path) -> tuple[Path, Path | None]:
    """16 kHz mono 32 kbps mp3 (a 20-minute video becomes ~5 MB). Speech recognition does not need
    more, and it keeps every video far below the upload limit. Without ffmpeg the original file is
    sent as it is. Returns (file to upload, temp dir to delete afterwards)."""
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg:
        tmp = Path(tempfile.mkdtemp(prefix="ebite_stt_"))
        out = tmp / "audio.mp3"
        r = subprocess.run(
            [ffmpeg, "-loglevel", "error", "-y", "-i", str(audio_path), "-vn", "-ac", "1", "-ar", "16000", "-b:a", "32k", str(out)],
            capture_output=True,
        )
        if r.returncode == 0 and out.exists() and out.stat().st_size > 0:
            return out, tmp
        shutil.rmtree(tmp, ignore_errors=True)
    return audio_path, None


def _retry_after(resp: requests.Response, default: float = 60.0) -> float:
    try:
        return float(resp.headers.get("retry-after", default))
    except ValueError:
        return default


def parse_words(body: dict) -> list[dict]:
    words = []
    for w in body.get("words") or []:
        text = str(w.get("word", "")).strip()
        if text:
            words.append({"text": text, "start_ms": float(w["start"]) * 1000, "end_ms": float(w["end"]) * 1000})
    return words


def transcribe(audio_path: Path, config: dict | None = None, log=print, sleep=time.sleep, post=requests.post) -> list[dict]:
    cfg = _settings(config)
    try:
        key = llm_client.read_key(cfg["key_file"])
    except llm_client.Fatal as e:
        raise SttUnavailable(f"받아쓰기 키를 읽을 수 없어요: {e}") from e

    upload, tmp = _compress(Path(audio_path))
    try:
        if upload.stat().st_size > MAX_UPLOAD_BYTES:
            raise SttUnavailable(f"오디오가 너무 커요 ({upload.stat().st_size // 1024 // 1024} MB)")
        data = upload.read_bytes()
        waited = 0.0
        attempt = 0
        while True:
            try:
                resp = post(
                    GROQ_URL,
                    headers={"Authorization": f"Bearer {key}"},
                    files={"file": (upload.name, data)},
                    data=[
                        ("model", cfg["model"]),
                        ("language", "en"),
                        ("response_format", "verbose_json"),
                        ("temperature", "0"),
                        ("timestamp_granularities[]", "word"),
                        ("timestamp_granularities[]", "segment"),
                    ],
                    timeout=cfg["timeout_s"],
                )
            except requests.RequestException as e:
                resp = None
                err = f"연결 오류: {e}"
            else:
                err = f"HTTP {resp.status_code}"

            if resp is not None and resp.status_code == 200:
                words = parse_words(resp.json())
                if not words:
                    raise SttNoSpeech("이 영상에서 음성을 인식하지 못했어요")
                return words
            if resp is not None and resp.status_code == 429:
                wait = _retry_after(resp)
                if waited + wait > cfg["max_wait_on_429_s"]:
                    raise SttUnavailable(f"받아쓰기 한도를 다 썼어요 (다시 열리기까지 약 {wait:.0f}초)")
                log(f"    받아쓰기 한도 도달, {wait:.0f}초 기다립니다")
                sleep(wait + 1)
                waited += wait + 1
                continue
            if resp is not None and resp.status_code in (401, 403):
                raise SttUnavailable(f"받아쓰기 키가 거부됐어요 (HTTP {resp.status_code})")
            if resp is not None and resp.status_code < 500 and resp.status_code != 408:
                # 400/413/422 ...: this file will not work no matter how often it is retried.
                raise SttRejected(f"받아쓰기 요청이 거절됐어요 (HTTP {resp.status_code}: {resp.text[:200]})")
            if attempt >= cfg["retries"]:
                raise SttUnavailable(f"받아쓰기 서버 오류가 계속돼요 ({err})")
            sleep(cfg["backoff_seconds"][min(attempt, len(cfg["backoff_seconds"]) - 1)])
            attempt += 1
    finally:
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)
