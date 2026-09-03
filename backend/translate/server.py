import json
from pathlib import Path

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from pipeline import extract_video_id, process_video

CACHE_DIR = Path(__file__).parent / "cache"
CACHE_DIR.mkdir(exist_ok=True)

app = FastAPI(title="EnglishBite ingest API")


class IngestRequest(BaseModel):
    url: str


def cache_path(video_id: str) -> Path:
    return CACHE_DIR / f"{video_id}.json"


@app.post("/videos")
def ingest_video(req: IngestRequest):
    try:
        video_id = extract_video_id(req.url)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    path = cache_path(video_id)
    if path.exists():
        return {"cached": True, **json.loads(path.read_text(encoding="utf-8"))}

    try:
        result = process_video(req.url)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Failed to process video: {e}")

    path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"cached": False, **result}


@app.get("/videos/{video_id}")
def get_video(video_id: str):
    path = cache_path(video_id)
    if not path.exists():
        raise HTTPException(status_code=404, detail="Not processed yet")
    return json.loads(path.read_text(encoding="utf-8"))


@app.get("/health")
def health():
    return {"status": "ok"}
