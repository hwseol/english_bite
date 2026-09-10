import json
import os
import queue
import shutil
import tempfile
import threading
from pathlib import Path

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel

from catalog import upsert_catalog_entry
from pipeline import UserFacingError, extract_video_id, process_uploaded_audio, process_video

CACHE_DIR = Path(__file__).parent / "cache"
CACHE_DIR.mkdir(exist_ok=True)
CATALOG_PATH = Path(__file__).parent / "catalog.json"

# Shared secret the Android app's admin-sync screen sends back - this endpoint lets anyone who
# has it kick off arbitrary server-side processing (and, via catalog_entry, arbitrary catalog
# entries), so it's gated even though this is a small personal project. Set via the
# ADMIN_TOKEN env var on the server (see englishbite-api.service); with no env var set the
# admin endpoint is disabled entirely rather than silently accepting an empty token.
ADMIN_TOKEN = os.environ.get("ADMIN_TOKEN")

app = FastAPI(title="EnglishBite ingest API")

_lock = threading.Lock()
_in_progress: set[str] = set()
_errors: dict[str, tuple[int, str]] = {}

# Whisper + NLLB + Ollama each hold their own model in memory and are CPU-heavy to run - the
# admin-sync flow can enqueue dozens of videos within a couple minutes, and firing off one
# thread per request (the original design, sized around a single interactive user submitting
# one video at a time) let that many run concurrently at once and OOM-killed the whole service,
# losing every in-flight job with nothing cached to show for it. A single background worker
# processes one video at a time instead - slower to catch up after a big batch, but bounded and
# won't take the server down.
_job_queue: "queue.Queue[tuple]" = queue.Queue()


def _worker_loop():
    while True:
        fn, args = _job_queue.get()
        try:
            fn(*args)
        finally:
            _job_queue.task_done()


threading.Thread(target=_worker_loop, daemon=True).start()


class IngestRequest(BaseModel):
    url: str


def cache_path(video_id: str) -> Path:
    return CACHE_DIR / f"{video_id}.json"


def _run_ingest(url: str, video_id: str):
    try:
        result = process_video(url)
        cache_path(video_id).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    except UserFacingError as e:
        with _lock:
            _errors[video_id] = (422, str(e))
    except Exception as e:
        with _lock:
            _errors[video_id] = (502, f"번역 처리 중 문제가 발생했어요: {e}")
    finally:
        with _lock:
            _in_progress.discard(video_id)


def _run_ingest_from_audio(video_id: str, audio_path: Path, catalog_entry: dict):
    try:
        result = process_uploaded_audio(video_id, audio_path)
        cache_path(video_id).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        upsert_catalog_entry(catalog_entry)
    except UserFacingError as e:
        with _lock:
            _errors[video_id] = (422, str(e))
    except Exception as e:
        with _lock:
            _errors[video_id] = (502, f"번역 처리 중 문제가 발생했어요: {e}")
    finally:
        shutil.rmtree(audio_path.parent, ignore_errors=True)
        with _lock:
            _in_progress.discard(video_id)


@app.post("/admin/ingest")
async def admin_ingest(
    video_id: str = Form(...),
    title: str = Form(...),
    channel: str = Form(...),
    thumbnail: str | None = Form(None),
    view_count: int = Form(0),
    duration: int = Form(0),
    upload_date: str = Form(""),
    timestamp: int = Form(0),
    audio: UploadFile = File(...),
    x_admin_token: str | None = Header(None),
):
    """Counterpart to catalog.py's yt-dlp-based discovery, for when the server's own IP is
    blocked by YouTube's bot detection: the Android app (run by the admin, from a residential/
    mobile IP) scrapes the channel and extracts this video's audio itself, then uploads both
    here. From here on this is identical to the rest of the pipeline - transcribe, translate,
    extract idioms, cache, and add to catalog.json."""
    if not ADMIN_TOKEN or x_admin_token != ADMIN_TOKEN:
        raise HTTPException(status_code=403, detail="Invalid admin token")

    path = cache_path(video_id)
    if path.exists():
        return {"status": "done", "cached": True, **json.loads(path.read_text(encoding="utf-8"))}

    with _lock:
        already_running = video_id in _in_progress
        _errors.pop(video_id, None)
        if not already_running:
            _in_progress.add(video_id)

    if already_running:
        return {"status": "processing", "video_id": video_id}

    tmp_dir = Path(tempfile.mkdtemp(prefix="ebite_admin_audio_"))
    audio_path = tmp_dir / (audio.filename or "audio")
    with audio_path.open("wb") as f:
        shutil.copyfileobj(audio.file, f)

    catalog_entry = {
        "video_id": video_id,
        "title": title,
        "channel": channel,
        "thumbnail": thumbnail,
        "view_count": view_count,
        "duration": duration,
        "upload_date": upload_date,
        "timestamp": timestamp,
    }
    _job_queue.put((_run_ingest_from_audio, (video_id, audio_path, catalog_entry)))
    return {"status": "processing", "video_id": video_id}


@app.post("/videos")
def ingest_video(req: IngestRequest):
    """Kick off ingestion and return immediately - the client polls
    GET /videos/{video_id} for the result. A translation run can take
    minutes, which is too long for a single held-open connection to
    survive a phone's screen sleeping/backgrounding or a tunnel's
    proxy timeout."""
    try:
        video_id = extract_video_id(req.url)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    path = cache_path(video_id)
    if path.exists():
        return {"status": "done", "cached": True, **json.loads(path.read_text(encoding="utf-8"))}

    with _lock:
        already_running = video_id in _in_progress
        _errors.pop(video_id, None)
        if not already_running:
            _in_progress.add(video_id)

    if not already_running:
        _job_queue.put((_run_ingest, (req.url, video_id)))

    return {"status": "processing", "video_id": video_id}


@app.get("/videos/{video_id}")
def get_video(video_id: str):
    path = cache_path(video_id)
    if path.exists():
        return {"status": "done", "cached": True, **json.loads(path.read_text(encoding="utf-8"))}

    with _lock:
        if video_id in _errors:
            status_code, detail = _errors.pop(video_id)
            raise HTTPException(status_code=status_code, detail=detail)
        if video_id in _in_progress:
            return {"status": "processing", "video_id": video_id}

    raise HTTPException(status_code=404, detail="Not found - submit it via POST /videos first")


@app.get("/catalog")
def get_catalog(channel: str | None = None):
    """Today's CNN/BBC uploads, as collected by catalog.py. Each entry's video_id is only
    included once it's actually ready to watch (already run through /videos), so tapping a
    catalog card in the app is always an instant "done" - never a first-time ingest wait."""
    if not CATALOG_PATH.exists():
        return []

    items = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
    if channel:
        items = [i for i in items if i["channel"].lower() == channel.lower()]

    ready = []
    for item in items:
        if cache_path(item["video_id"]).exists():
            ready.append(item)
    return ready


@app.get("/health")
def health():
    return {"status": "ok"}
