import json
import threading
from pathlib import Path

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from pipeline import UserFacingError, extract_video_id, process_video

CACHE_DIR = Path(__file__).parent / "cache"
CACHE_DIR.mkdir(exist_ok=True)

app = FastAPI(title="EnglishBite ingest API")

_lock = threading.Lock()
_in_progress: set[str] = set()
_errors: dict[str, tuple[int, str]] = {}


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
        threading.Thread(target=_run_ingest, args=(req.url, video_id), daemon=True).start()

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


@app.get("/health")
def health():
    return {"status": "ok"}
