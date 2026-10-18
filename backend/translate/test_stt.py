"""Offline checks for stt.py (Groq Whisper). A fake HTTP post stands in for Groq, so nothing here
touches the network or needs a key:

    python test_stt.py
"""

import tempfile
from pathlib import Path

import requests

import pipeline
import stt

failures: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(("ok   " if ok else "FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        failures.append(name)


tmp = Path(tempfile.mkdtemp(prefix="stt_test_"))
audio = tmp / "audio.webm"
audio.write_bytes(b"not really audio")
keyfile = tmp / "key.txt"
keyfile.write_text("gsk_fake\n", encoding="utf-8")
CONFIG = {"stt": {"key_file": str(keyfile), "max_wait_on_429_s": 100, "retries": 2, "backoff_seconds": [1, 2]}}
stt.shutil.which = lambda name: None  # no ffmpeg: the original file is uploaded as it is


class Resp:
    def __init__(self, status, body=None, headers=None, text=""):
        self.status_code, self._body, self.headers, self.text = status, body or {}, headers or {}, text

    def json(self):
        return self._body


def fake_post(*replies):
    calls = []
    queue = list(replies)

    def post(url, **kw):
        calls.append(kw)
        r = queue.pop(0)
        if isinstance(r, Exception):
            raise r
        return r

    post.calls = calls
    return post


GOOD = Resp(200, {"words": [{"word": " We", "start": 0.02, "end": 0.2}, {"word": "had", "start": 0.2, "end": 0.46}, {"word": " ", "start": 1, "end": 1}]})


def run(*replies):
    sleeps = []
    post = fake_post(*replies)
    try:
        return stt.transcribe(audio, CONFIG, log=lambda m: None, sleep=sleeps.append, post=post), sleeps, post, None
    except Exception as e:  # noqa: BLE001
        return None, sleeps, post, e


words, sleeps, post, err = run(GOOD)
check("words parsed to the pipeline's format (ms, stripped, blanks dropped)",
      words == [{"text": "We", "start_ms": 20.0, "end_ms": 200.0}, {"text": "had", "start_ms": 200.0, "end_ms": 460.0}], str(words))
sent = dict(post.calls[0]["data"])
check("asks for English, word timestamps, the turbo model",
      sent["model"] == "whisper-large-v3-turbo" and sent["language"] == "en"
      and ("timestamp_granularities[]", "word") in post.calls[0]["data"], str(post.calls[0]["data"]))
check("key is sent as a bearer token", post.calls[0]["headers"]["Authorization"] == "Bearer gsk_fake")

words, sleeps, post, err = run(Resp(429, headers={"retry-after": "30"}), GOOD)
check("429 with a short wait: waits, then succeeds", words is not None and len(post.calls) == 2 and sleeps == [31.0], f"{sleeps} {err}")

words, sleeps, post, err = run(Resp(429, headers={"retry-after": "3000"}))
check("429 asking for a long wait: gives up as SttUnavailable (video is deferred)", isinstance(err, stt.SttUnavailable) and not sleeps, str(err))

words, sleeps, post, err = run(Resp(500), Resp(502), GOOD)
check("5xx twice then fine: retried with backoff", words is not None and sleeps == [1, 2], f"{sleeps} {err}")

words, sleeps, post, err = run(Resp(500), Resp(500), Resp(500))
check("5xx for good: SttUnavailable after the retries", isinstance(err, stt.SttUnavailable) and len(post.calls) == 3, str(err))

words, sleeps, post, err = run(requests.ConnectionError("down"), GOOD)
check("a dropped connection is retried", words is not None, str(err))

words, sleeps, post, err = run(Resp(401))
check("401 (bad key): SttUnavailable, no retry", isinstance(err, stt.SttUnavailable) and len(post.calls) == 1, str(err))

words, sleeps, post, err = run(Resp(400, text="bad file"))
check("400 (this file is refused): SttRejected - a per-video failure, not a reason to stop the run",
      isinstance(err, stt.SttRejected) and not isinstance(err, stt.SttUnavailable), str(err))

words, sleeps, post, err = run(Resp(200, {"words": []}))
check("no words: SttNoSpeech", isinstance(err, stt.SttNoSpeech), str(err))

try:
    stt.transcribe(audio, {"stt": {"key_file": str(tmp / "missing.txt")}}, log=lambda m: None, post=fake_post())
    err = None
except Exception as e:  # noqa: BLE001
    err = e
check("missing key file: SttUnavailable", isinstance(err, stt.SttUnavailable), str(err))

big = tmp / "big.webm"
big.write_bytes(b"0" * (stt.MAX_UPLOAD_BYTES + 1))
try:
    stt.transcribe(big, CONFIG, log=lambda m: None, post=fake_post())
    err = None
except Exception as e:  # noqa: BLE001
    err = e
check("file over the upload limit: SttUnavailable before any request", isinstance(err, stt.SttUnavailable), str(err))

stt_transcribe = stt.transcribe
stt.transcribe = lambda p, *a, **k: (_ for _ in ()).throw(stt.SttNoSpeech("x"))
try:
    pipeline._transcribe_audio(audio)
    err = None
except Exception as e:  # noqa: BLE001
    err = e
stt.transcribe = stt_transcribe
check("pipeline turns 'no speech' into its user-facing error", isinstance(err, pipeline.UserFacingError), str(err))

print()
print("ALL PASSED" if not failures else f"{len(failures)} FAILED: {failures}")
raise SystemExit(1 if failures else 0)
