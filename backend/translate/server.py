import json
import os
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

import auth_db
from catalog import upsert_catalog_entry

CACHE_DIR = Path(__file__).parent / "cache"
CACHE_DIR.mkdir(exist_ok=True)
CATALOG_PATH = Path(__file__).parent / "catalog.json"

# Shared secret the PC-side admin_sync.py sends along with each finished video. Set via the
# ADMIN_TOKEN env var on the server (see englishbite-api.service); with no env var set the admin
# endpoint is disabled entirely rather than silently accepting an empty token.
ADMIN_TOKEN = os.environ.get("ADMIN_TOKEN")

# This server only serves finished results - the heavy work (Whisper transcription, NLLB
# translation, Ollama idiom extraction) runs on the PC and arrives via /admin/publish. That is
# what lets this run on a tiny instance instead of the 8GB one those models needed.
app = FastAPI(title="EnglishBite API")
# Only the GitHub Pages deletion form (docs/delete-account.html) calls this API from a browser;
# the Android app isn't subject to CORS.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["https://hwseol.github.io"],
    allow_methods=["POST"],
    allow_headers=["Content-Type"],
)
auth_db.init_db()


class SignupRequest(BaseModel):
    email: str
    nickname: str
    password: str


class LoginRequest(BaseModel):
    email: str
    password: str


class ChangePasswordRequest(BaseModel):
    email: str
    old_password: str
    new_password: str


class IngestRequest(BaseModel):
    url: str


class PublishRequest(BaseModel):
    result: dict
    catalog_entry: dict


def cache_path(video_id: str) -> Path:
    return CACHE_DIR / f"{video_id}.json"


def extract_video_id(url: str) -> str:
    parsed = urlparse(url)
    if parsed.hostname in ("youtu.be",):
        return parsed.path.lstrip("/")
    if parsed.hostname and "youtube.com" in parsed.hostname:
        if parsed.path == "/watch":
            return parse_qs(parsed.query)["v"][0]
        if parsed.path.startswith("/shorts/"):
            return parsed.path.split("/")[2]
    raise ValueError(f"Could not parse a video ID from URL: {url}")


def _done_response(video_id: str) -> dict:
    return {"status": "done", "cached": True, **json.loads(cache_path(video_id).read_text(encoding="utf-8"))}


@app.post("/admin/publish")
def admin_publish(req: PublishRequest, x_admin_token: str | None = Header(None)):
    """Counterpart to admin_sync.py: the PC has already transcribed, translated and extracted
    idioms for this video, so this just stores the finished result and adds the video to
    catalog.json."""
    if not ADMIN_TOKEN or x_admin_token != ADMIN_TOKEN:
        raise HTTPException(status_code=403, detail="Invalid admin token")

    video_id = req.catalog_entry.get("video_id")
    if not video_id or req.result.get("video_id") != video_id:
        raise HTTPException(status_code=400, detail="video_id mismatch between result and catalog_entry")

    cache_path(video_id).write_text(json.dumps(req.result, ensure_ascii=False, indent=2), encoding="utf-8")
    upsert_catalog_entry(req.catalog_entry)
    return {"status": "done", "video_id": video_id}


@app.post("/videos")
def ingest_video(req: IngestRequest):
    """Kept for the app's existing flow (it POSTs, then polls GET /videos/{id}): every video the
    catalog offers is already processed, so this answers "done" immediately for those. A video
    that was never processed can't be produced here anymore - the server no longer runs the
    models."""
    try:
        video_id = extract_video_id(req.url)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    if cache_path(video_id).exists():
        return _done_response(video_id)
    raise HTTPException(status_code=404, detail="아직 준비되지 않은 영상이에요. 목록에 있는 다른 영상을 선택해주세요.")


@app.get("/videos/{video_id}")
def get_video(video_id: str):
    if cache_path(video_id).exists():
        return _done_response(video_id)
    raise HTTPException(status_code=404, detail="아직 준비되지 않은 영상이에요.")


@app.get("/catalog")
def get_catalog(channel: str | None = None):
    """Today's CNN/BBC uploads, as collected by admin_sync.py. Each entry's video_id is only
    included once it's actually ready to watch, so tapping a catalog card in the app is always
    an instant "done"."""
    if not CATALOG_PATH.exists():
        return []

    items = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
    if channel:
        items = [i for i in items if i["channel"].lower() == channel.lower()]

    return [item for item in items if cache_path(item["video_id"]).exists()]


@app.post("/auth/signup")
def signup(req: SignupRequest):
    try:
        user = auth_db.create_user(req.email, req.nickname, req.password)
    except auth_db.AuthError as e:
        raise HTTPException(status_code=400, detail=str(e))
    token = auth_db.create_session(user["id"])
    return {"token": token, **auth_db.user_public(user)}


@app.post("/auth/login")
def login(req: LoginRequest):
    try:
        user = auth_db.verify_login(req.email, req.password)
    except auth_db.AuthError as e:
        raise HTTPException(status_code=400, detail=str(e))
    token = auth_db.create_session(user["id"])
    return {"token": token, **auth_db.user_public(user)}


@app.post("/auth/change-password")
def change_password(req: ChangePasswordRequest):
    try:
        user = auth_db.change_password(req.email, req.old_password, req.new_password)
    except auth_db.AuthError as e:
        raise HTTPException(status_code=400, detail=str(e))
    # Every old session was just dropped, so hand back a fresh one for this device.
    token = auth_db.create_session(user["id"])
    return {"token": token, **auth_db.user_public(user)}


@app.post("/auth/delete")
def delete_account(req: LoginRequest):
    """Account deletion, required by Google Play for any app with accounts. Used both by the
    in-app "회원 탈퇴" button and by the public web form (docs/delete-account.html)."""
    try:
        auth_db.delete_user(req.email, req.password)
    except auth_db.AuthError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return {"status": "deleted"}


@app.get("/auth/me")
def me(authorization: str | None = Header(None)):
    """authorization is the raw session token (no "Bearer " prefix - there's nothing else that
    would ever populate this header, so the extra parsing isn't worth it)."""
    user = auth_db.get_user_by_token(authorization) if authorization else None
    if user is None:
        raise HTTPException(status_code=401, detail="로그인이 필요해요.")
    return auth_db.user_public(user)


@app.get("/health")
def health():
    return {"status": "ok"}
