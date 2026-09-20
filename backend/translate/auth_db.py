"""User accounts, kept in a separate SQLite file from the content pipeline's catalog.json/cache
so a problem on one side never touches the other. Deliberately minimal: just email, nickname,
and a password hash - no other personal info, and no ORM/migration framework since one table
plus one sessions table doesn't need either.
"""

import hashlib
import secrets
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

DB_PATH = Path(__file__).parent / "users.db"

SESSION_LIFETIME = timedelta(days=30)

# ~260k rounds of PBKDF2-SHA256 matches Django's current default cost - deliberately slow
# enough to make brute-forcing a stolen hash impractical, cheap enough that one login request
# doesn't add noticeable latency.
PBKDF2_ITERATIONS = 260_000


class AuthError(Exception):
    """Any user-facing auth failure (duplicate email, wrong password, bad input) - server.py
    catches this and returns its message as a 400 as-is, so messages here are Korean and
    already fit to show the user directly."""


def _connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    conn = _connect()
    try:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                email TEXT NOT NULL UNIQUE,
                nickname TEXT NOT NULL,
                password_hash TEXT,
                password_salt TEXT,
                google_sub TEXT UNIQUE,
                created_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                token TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id),
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL
            )
            """
        )
        conn.commit()
    finally:
        conn.close()


def _hash_password(password: str, salt_hex: str | None = None) -> tuple[str, str]:
    salt_hex = salt_hex or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), bytes.fromhex(salt_hex), PBKDF2_ITERATIONS
    )
    return digest.hex(), salt_hex


def user_public(row: sqlite3.Row) -> dict:
    """The subset of a user row that's safe to send back to the client - never the password
    hash/salt."""
    return {"email": row["email"], "nickname": row["nickname"]}


def create_user(email: str, nickname: str, password: str) -> sqlite3.Row:
    email = email.strip().lower()
    nickname = nickname.strip()
    if "@" not in email or "." not in email.split("@")[-1]:
        raise AuthError("올바른 이메일 주소를 입력해주세요.")
    if len(password) < 8:
        raise AuthError("비밀번호는 8자 이상이어야 해요.")
    if not nickname:
        raise AuthError("닉네임을 입력해주세요.")

    password_hash, salt = _hash_password(password)
    created_at = datetime.now(timezone.utc).isoformat()
    conn = _connect()
    try:
        try:
            cur = conn.execute(
                "INSERT INTO users (email, nickname, password_hash, password_salt, created_at) "
                "VALUES (?, ?, ?, ?, ?)",
                (email, nickname, password_hash, salt, created_at),
            )
        except sqlite3.IntegrityError:
            raise AuthError("이미 가입된 이메일이에요.")
        conn.commit()
        return conn.execute("SELECT * FROM users WHERE id = ?", (cur.lastrowid,)).fetchone()
    finally:
        conn.close()


def verify_login(email: str, password: str) -> sqlite3.Row:
    email = email.strip().lower()
    conn = _connect()
    try:
        row = conn.execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
    finally:
        conn.close()

    if row is None or not row["password_hash"]:
        raise AuthError("이메일 또는 비밀번호가 올바르지 않아요.")
    digest, _ = _hash_password(password, row["password_salt"])
    if not secrets.compare_digest(digest, row["password_hash"]):
        raise AuthError("이메일 또는 비밀번호가 올바르지 않아요.")
    return row


def create_session(user_id: int) -> str:
    token = secrets.token_urlsafe(32)
    now = datetime.now(timezone.utc)
    conn = _connect()
    try:
        conn.execute(
            "INSERT INTO sessions (token, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
            (token, user_id, now.isoformat(), (now + SESSION_LIFETIME).isoformat()),
        )
        conn.commit()
    finally:
        conn.close()
    return token


def get_user_by_token(token: str) -> sqlite3.Row | None:
    conn = _connect()
    try:
        session = conn.execute("SELECT * FROM sessions WHERE token = ?", (token,)).fetchone()
        if session is None:
            return None
        if datetime.fromisoformat(session["expires_at"]) < datetime.now(timezone.utc):
            conn.execute("DELETE FROM sessions WHERE token = ?", (token,))
            conn.commit()
            return None
        return conn.execute("SELECT * FROM users WHERE id = ?", (session["user_id"],)).fetchone()
    finally:
        conn.close()
