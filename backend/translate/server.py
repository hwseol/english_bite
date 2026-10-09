import hmac
import json
import os
import re
import time
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

import auth_db
from catalog import upsert_catalog_entry

# Everything the server stores lives under DATA_DIR. Production leaves EB_DATA_DIR unset (data
# sits next to the code, as it always has); the dev instance points it at its own folder so
# testing never touches real accounts or videos.
DATA_DIR = Path(os.environ.get("EB_DATA_DIR") or Path(__file__).parent)
DATA_DIR.mkdir(parents=True, exist_ok=True)
CACHE_DIR = DATA_DIR / "cache"
CACHE_DIR.mkdir(exist_ok=True)
CATALOG_PATH = DATA_DIR / "catalog.json"
CRASH_LOG = DATA_DIR / "crashes.jsonl"
CRASH_LOG_MAX_BYTES = 5 * 1024 * 1024  # stop accepting once it's this big - nobody is reading a flood

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


class CrashReport(BaseModel):
    app_version: int = 0
    device: str = ""
    android: str = ""
    trace: str = ""


class IngestRequest(BaseModel):
    url: str


class PublishRequest(BaseModel):
    result: dict
    catalog_entry: dict


# A YouTube video ID is exactly 11 URL-safe characters. Anything else is rejected before it can
# reach the filesystem: the ID becomes a file name under CACHE_DIR, and an ID like "../x" would
# otherwise let a request read any *.json file the server user can see.
VIDEO_ID_RE = re.compile(r"[A-Za-z0-9_-]{11}")


def cache_path(video_id: str) -> Path:
    if not VIDEO_ID_RE.fullmatch(video_id):
        raise HTTPException(status_code=404, detail="아직 준비되지 않은 영상이에요.")
    return CACHE_DIR / f"{video_id}.json"


def extract_video_id(url: str) -> str:
    parsed = urlparse(url)
    video_id = ""
    host = parsed.hostname or ""
    if host == "youtu.be":
        video_id = parsed.path.lstrip("/")
    elif host == "youtube.com" or host.endswith(".youtube.com"):
        if parsed.path == "/watch":
            video_id = (parse_qs(parsed.query).get("v") or [""])[0]
        elif parsed.path.startswith("/shorts/"):
            parts = parsed.path.split("/")
            video_id = parts[2] if len(parts) > 2 else ""
    if not VIDEO_ID_RE.fullmatch(video_id):
        raise ValueError("올바른 YouTube 주소가 아니에요.")
    return video_id


def _done_response(video_id: str) -> dict:
    return {"status": "done", "cached": True, **json.loads(cache_path(video_id).read_text(encoding="utf-8"))}


@app.post("/admin/publish")
def admin_publish(req: PublishRequest, x_admin_token: str | None = Header(None)):
    """Counterpart to admin_sync.py: the PC has already transcribed, translated and extracted
    idioms for this video, so this just stores the finished result and adds the video to
    catalog.json."""
    # compare_digest: a plain != leaks, through response timing, how much of a guess was right.
    if not ADMIN_TOKEN or not hmac.compare_digest((x_admin_token or "").encode(), ADMIN_TOKEN.encode()):
        raise HTTPException(status_code=403, detail="Invalid admin token")

    video_id = req.catalog_entry.get("video_id")
    if not video_id or req.result.get("video_id") != video_id:
        raise HTTPException(status_code=400, detail="video_id mismatch between result and catalog_entry")
    if not VIDEO_ID_RE.fullmatch(str(video_id)):
        raise HTTPException(status_code=400, detail="invalid video_id")

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

    return [
        item for item in items
        if VIDEO_ID_RE.fullmatch(str(item.get("video_id", ""))) and cache_path(item["video_id"]).exists()
    ]


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


@app.post("/telemetry/crash")
def report_crash(req: CrashReport):
    """The app saves a stack trace when it crashes and sends it here on its next launch. Only
    app version, device model, Android version and the trace itself - nothing about the person.
    Read them with view_crashes.py."""
    if CRASH_LOG.exists() and CRASH_LOG.stat().st_size > CRASH_LOG_MAX_BYTES:
        return {"status": "ignored"}
    entry = {
        "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "app_version": req.app_version,
        "device": req.device[:80],
        "android": req.android[:20],
        "trace": req.trace[:6000],
    }
    with CRASH_LOG.open("a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    return {"status": "ok"}


@app.get("/health")
def health():
    """Liveness plus a freshness signal for an external uptime monitor: `fresh` goes false when
    nothing new has been published for 48h, i.e. the PC-side sync has silently stopped (PC off,
    yt-dlp broken, Ollama down). The sync only logs its own failures, so this is how they
    surface."""
    age_hours = None
    if CATALOG_PATH.exists():
        age_hours = round((time.time() - CATALOG_PATH.stat().st_mtime) / 3600, 1)
    return {"status": "ok", "catalog_age_hours": age_hours, "fresh": age_hours is not None and age_hours < 48}
