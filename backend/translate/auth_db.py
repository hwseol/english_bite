"""User accounts, kept in a separate SQLite file from the content pipeline's catalog.json/cache
so a problem on one side never touches the other. Deliberately minimal: just email, nickname,
and a password hash - no other personal info, and no ORM/migration framework since one table
plus one sessions table doesn't need either.
"""

import hashlib
import json
import os
import re
import secrets
import smtplib
import sqlite3
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage
from pathlib import Path

# EB_DATA_DIR keeps the dev instance's accounts apart from production's (see server.py).
DB_PATH = Path(os.environ.get("EB_DATA_DIR") or Path(__file__).parent) / "users.db"

SESSION_LIFETIME = timedelta(days=30)

# Google sign-in: the OAuth *web* client ID the phone asks Google for an ID token with (public, but
# set on the server like the rest of its configuration). Unset means Google sign-in is off.
GOOGLE_CLIENT_ID = os.environ.get("EB_GOOGLE_CLIENT_ID", "").strip()

# Verification / reset codes go out by email through an SMTP account set on the server (e.g. Gmail
# with an app password): EB_SMTP_HOST / _PORT / _USER / _PASSWORD, and _FROM for the sender shown.
# EB_MAIL_DEBUG_LOG (dev only) appends the mails to a file instead of sending them, so the flow can be
# tried without an SMTP account.
SMTP = {k: os.environ.get(f"EB_SMTP_{k.upper()}", "").strip() for k in ("host", "port", "user", "password", "from")}
MAIL_DEBUG_LOG = os.environ.get("EB_MAIL_DEBUG_LOG", "").strip()
CODE_LIFETIME = timedelta(minutes=15)
CODE_MAX_TRIES = 5            # wrong codes before the code is void
CODE_RESEND_AFTER = timedelta(seconds=60)
MAIL_DAILY_CAP = int(os.environ.get("EB_MAIL_DAILY_CAP", "200"))  # Gmail allows ~500/day; keep well under

EMAIL_RE = re.compile(r"^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$")

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
        # One pending code per (email, purpose); only a salted hash of it is stored.
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS email_codes (
                email TEXT NOT NULL,
                purpose TEXT NOT NULL,
                code_hash TEXT NOT NULL,
                code_salt TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                sent_at TEXT NOT NULL,
                tries INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (email, purpose)
            )
            """
        )
        conn.execute("CREATE TABLE IF NOT EXISTS mail_log (sent_at TEXT NOT NULL)")
        # Additive migrations for databases created by older versions of this file.
        cols = {r["name"] for r in conn.execute("PRAGMA table_info(users)")}
        if "google_sub" not in cols:
            conn.execute("ALTER TABLE users ADD COLUMN google_sub TEXT")
        conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_google_sub ON users(google_sub) WHERE google_sub IS NOT NULL")
        if "email_verified" not in cols:
            conn.execute("ALTER TABLE users ADD COLUMN email_verified INTEGER NOT NULL DEFAULT 0")
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
    return {
        "email": row["email"],
        "nickname": row["nickname"],
        # Additive fields (older app versions ignore them).
        "email_verified": bool(row["email_verified"]),
        "has_password": bool(row["password_hash"]),
    }


def create_user(email: str, nickname: str, password: str, verified: bool = False) -> sqlite3.Row:
    email = email.strip().lower()
    nickname = nickname.strip()
    if not EMAIL_RE.match(email):
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
                "INSERT INTO users (email, nickname, password_hash, password_salt, created_at, email_verified) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (email, nickname, password_hash, salt, created_at, 1 if verified else 0),
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

    if row is not None and not row["password_hash"]:
        raise AuthError("구글로 가입한 계정이에요. 구글로 로그인하거나, '비밀번호 찾기'로 비밀번호를 만들어 주세요.")
    if row is None:
        raise AuthError("이메일 또는 비밀번호가 올바르지 않아요.")
    digest, _ = _hash_password(password, row["password_salt"])
    if not secrets.compare_digest(digest, row["password_hash"]):
        raise AuthError("이메일 또는 비밀번호가 올바르지 않아요.")
    return row


def delete_user(email: str, password: str) -> None:
    """Permanently removes the account and every session belonging to it. Re-verifies the
    password rather than trusting a session token, so the same call also serves the public
    web deletion form where the person isn't logged in anywhere."""
    user = verify_login(email, password)
    conn = _connect()
    try:
        conn.execute("DELETE FROM sessions WHERE user_id = ?", (user["id"],))
        conn.execute("DELETE FROM users WHERE id = ?", (user["id"],))
        conn.commit()
    finally:
        conn.close()


def _set_password(user_id: int, new_password: str) -> None:
    """Stores a new password and signs the account out everywhere (drops every session)."""
    password_hash, salt = _hash_password(new_password)
    conn = _connect()
    try:
        conn.execute(
            "UPDATE users SET password_hash = ?, password_salt = ? WHERE id = ?",
            (password_hash, salt, user_id),
        )
        conn.execute("DELETE FROM sessions WHERE user_id = ?", (user_id,))
        conn.commit()
    finally:
        conn.close()


def change_password(email: str, old_password: str, new_password: str) -> sqlite3.Row:
    """Self-service change: proves knowledge of the old password, then invalidates every
    existing session. Returns the user row so the caller can issue a fresh session."""
    user = verify_login(email, old_password)
    if len(new_password) < 8:
        raise AuthError("새 비밀번호는 8자 이상이어야 해요.")
    _set_password(user["id"], new_password)
    return user


def admin_reset_password(email: str, new_password: str) -> bool:
    """Operator-side reset for someone who forgot their password (see reset_password.py) -
    there's no email sender on this server, so recovery goes through the developer. Returns
    False if no such account exists."""
    email = email.strip().lower()
    conn = _connect()
    try:
        row = conn.execute("SELECT id FROM users WHERE email = ?", (email,)).fetchone()
    finally:
        conn.close()
    if row is None:
        return False
    _set_password(row["id"], new_password)
    return True


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


# --- email codes (sign-up verification and password reset) -------------------------------------

def can_email() -> bool:
    return bool((SMTP["host"] and SMTP["user"] and SMTP["password"]) or MAIL_DEBUG_LOG)


def _deliver(to: str, subject: str, body: str) -> None:
    if SMTP["host"] and SMTP["user"] and SMTP["password"]:
        msg = EmailMessage()
        msg["Subject"] = subject
        msg["From"] = SMTP["from"] or SMTP["user"]
        msg["To"] = to
        msg.set_content(body)
        with smtplib.SMTP(SMTP["host"], int(SMTP["port"] or 587), timeout=15) as s:
            s.starttls()
            s.login(SMTP["user"], SMTP["password"])
            s.send_message(msg)
    elif MAIL_DEBUG_LOG:
        with open(MAIL_DEBUG_LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps({"to": to, "subject": subject, "body": body}, ensure_ascii=False) + "\n")
    else:
        raise RuntimeError("no mail account configured")


_PURPOSE_TEXT = {
    "signup": ("회원가입 인증번호", "앱의 회원가입 화면에 입력해 주세요."),
    "reset": ("비밀번호 재설정 인증번호", "앱의 비밀번호 재설정 화면에 입력해 주세요."),
}


def send_code(email: str, purpose: str, deliver=None) -> None:
    """Emails a 6-digit code (valid 15 minutes, 5 tries, one per minute per address, and a daily cap
    on the whole server so the mail account is never exhausted by someone hammering this)."""
    if purpose not in _PURPOSE_TEXT:
        raise AuthError("요청이 올바르지 않아요.")
    email = email.strip().lower()
    if not EMAIL_RE.match(email):
        raise AuthError("올바른 이메일 주소를 입력해주세요.")
    if deliver is None and not can_email():
        raise AuthError("지금은 인증 메일을 보낼 수 없어요. 잠시 후 다시 시도해 주세요.")

    now = datetime.now(timezone.utc)
    conn = _connect()
    try:
        exists = conn.execute("SELECT 1 FROM users WHERE email = ?", (email,)).fetchone()
        if purpose == "signup" and exists:
            raise AuthError("이미 가입된 이메일이에요. 로그인해 주세요.")
        if purpose == "reset" and not exists:
            raise AuthError("가입되지 않은 이메일이에요. 주소를 확인하거나 새로 가입해 주세요.")
        prev = conn.execute("SELECT sent_at FROM email_codes WHERE email = ? AND purpose = ?", (email, purpose)).fetchone()
        if prev and now - datetime.fromisoformat(prev["sent_at"]) < CODE_RESEND_AFTER:
            raise AuthError("방금 인증번호를 보냈어요. 1분 뒤에 다시 시도해 주세요.")
        sent_today = conn.execute("SELECT COUNT(*) FROM mail_log WHERE sent_at > ?", ((now - timedelta(hours=24)).isoformat(),)).fetchone()[0]
        if sent_today >= MAIL_DAILY_CAP:
            raise AuthError("오늘 보낼 수 있는 인증 메일이 모두 소진됐어요. 내일 다시 시도해 주세요.")
        code = f"{secrets.randbelow(1_000_000):06d}"
        code_hash, salt = _hash_password(code)
        conn.execute(
            "INSERT OR REPLACE INTO email_codes (email, purpose, code_hash, code_salt, expires_at, sent_at, tries) "
            "VALUES (?, ?, ?, ?, ?, ?, 0)",
            (email, purpose, code_hash, salt, (now + CODE_LIFETIME).isoformat(), now.isoformat()),
        )
        conn.execute("INSERT INTO mail_log (sent_at) VALUES (?)", (now.isoformat(),))
        conn.execute("DELETE FROM mail_log WHERE sent_at < ?", ((now - timedelta(days=2)).isoformat(),))
        conn.commit()
    finally:
        conn.close()

    title, how = _PURPOSE_TEXT[purpose]
    body = (f"EnglishBite {title}: {code}\n\n{how} 15분 동안 쓸 수 있어요.\n"
            "직접 요청하지 않았다면 이 메일은 무시하셔도 돼요.")
    try:
        (deliver or _deliver)(email, f"[EnglishBite] {title} {code}", body)
    except Exception:
        # Not delivered: forget the code so the person can ask again straight away.
        c = _connect()
        try:
            c.execute("DELETE FROM email_codes WHERE email = ? AND purpose = ?", (email, purpose))
            c.commit()
        finally:
            c.close()
        raise AuthError("메일을 보내지 못했어요. 잠시 후 다시 시도해 주세요.")


def check_code(email: str, purpose: str, code: str, consume: bool = True) -> None:
    """Raises AuthError unless `code` is the live code for this address. A wrong guess counts
    against the 5 tries; success deletes the code (unless consume=False)."""
    email = email.strip().lower()
    wrong = AuthError("인증번호가 맞지 않거나 만료됐어요. 다시 받아 주세요.")
    conn = _connect()
    try:
        r = conn.execute("SELECT * FROM email_codes WHERE email = ? AND purpose = ?", (email, purpose)).fetchone()
        if not r or datetime.now(timezone.utc) > datetime.fromisoformat(r["expires_at"]) or r["tries"] >= CODE_MAX_TRIES:
            raise wrong
        digest, _ = _hash_password((code or "").strip(), r["code_salt"])
        if not secrets.compare_digest(digest, r["code_hash"]):
            conn.execute("UPDATE email_codes SET tries = tries + 1 WHERE email = ? AND purpose = ?", (email, purpose))
            conn.commit()
            raise wrong
        if consume:
            conn.execute("DELETE FROM email_codes WHERE email = ? AND purpose = ?", (email, purpose))
            conn.commit()
    finally:
        conn.close()


def consume_code(email: str, purpose: str) -> None:
    conn = _connect()
    try:
        conn.execute("DELETE FROM email_codes WHERE email = ? AND purpose = ?", (email.strip().lower(), purpose))
        conn.commit()
    finally:
        conn.close()


def confirm_reset(email: str, code: str, new_password: str) -> sqlite3.Row:
    """New password with the emailed code (also how a Google-only account adds a password). Proves
    the address, so it is marked verified; every other session is signed out."""
    if len(new_password) < 8:
        raise AuthError("비밀번호는 8자 이상이어야 해요.")
    check_code(email, "reset", code)
    email = email.strip().lower()
    conn = _connect()
    try:
        row = conn.execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
    finally:
        conn.close()
    if row is None:
        raise AuthError("인증번호가 맞지 않거나 만료됐어요. 다시 받아 주세요.")
    _set_password(row["id"], new_password)
    conn = _connect()
    try:
        conn.execute("UPDATE users SET email_verified = 1 WHERE id = ?", (row["id"],))
        conn.commit()
        return conn.execute("SELECT * FROM users WHERE id = ?", (row["id"],)).fetchone()
    finally:
        conn.close()


# --- Google sign-in ------------------------------------------------------------------------------

def verify_google_token(id_token: str, fetch=None) -> dict:
    """Google's own check of an ID token (one small request per sign-in): it must be issued for our
    client and carry a verified email. Returns the token's claims."""
    if not GOOGLE_CLIENT_ID:
        raise AuthError("구글 로그인은 아직 준비 중이에요.")
    url = "https://oauth2.googleapis.com/tokeninfo?" + urllib.parse.urlencode({"id_token": id_token})
    try:
        if fetch:
            claims = fetch(url)
        else:
            with urllib.request.urlopen(url, timeout=10) as r:
                claims = json.loads(r.read())
    except Exception:
        raise AuthError("구글 로그인을 확인하지 못했어요. 다시 시도해 주세요.")
    if claims.get("aud") != GOOGLE_CLIENT_ID or claims.get("iss") not in ("accounts.google.com", "https://accounts.google.com"):
        raise AuthError("구글 로그인을 확인하지 못했어요. 다시 시도해 주세요.")
    if str(claims.get("email_verified")).lower() != "true" or not claims.get("email") or not claims.get("sub"):
        raise AuthError("구글 계정의 이메일이 확인되지 않았어요.")
    return claims


def google_user(claims: dict) -> sqlite3.Row:
    """The account for a Google sign-in: the one already linked to this Google id, else the one with
    the same email (Google vouches for it, so the two are linked), else a new account named after the
    Google profile - with no password of its own."""
    sub, email = str(claims["sub"]), claims["email"].strip().lower()
    conn = _connect()
    try:
        row = conn.execute("SELECT * FROM users WHERE google_sub = ?", (sub,)).fetchone()
        if row:
            return row
        row = conn.execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
        if row:
            conn.execute("UPDATE users SET google_sub = ?, email_verified = 1 WHERE id = ?", (sub, row["id"]))
            conn.commit()
            return conn.execute("SELECT * FROM users WHERE id = ?", (row["id"],)).fetchone()
        nickname = (claims.get("name") or email.split("@")[0]).strip()[:20] or "학습자"
        cur = conn.execute(
            "INSERT INTO users (email, nickname, password_hash, password_salt, google_sub, created_at, email_verified) "
            "VALUES (?, ?, NULL, NULL, ?, ?, 1)",
            (email, nickname, sub, datetime.now(timezone.utc).isoformat()),
        )
        conn.commit()
        return conn.execute("SELECT * FROM users WHERE id = ?", (cur.lastrowid,)).fetchone()
    finally:
        conn.close()


def delete_by_session(user: sqlite3.Row, password: str | None) -> None:
    """In-app deletion for the signed-in account: with its password when it has one (a borrowed
    unlocked phone can't delete it), or on the session alone for a Google account, which has none."""
    if user["password_hash"]:
        verify_login(user["email"], password or "")
    conn = _connect()
    try:
        conn.execute("DELETE FROM sessions WHERE user_id = ?", (user["id"],))
        conn.execute("DELETE FROM email_codes WHERE email = ?", (user["email"],))
        conn.execute("DELETE FROM users WHERE id = ?", (user["id"],))
        conn.commit()
    finally:
        conn.close()


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
