"""Regression checks for the API's input handling and admin gate. Runs against a throwaway data
directory, so it never touches real accounts or videos:

    python test_server_api.py

Exits non-zero (and says which check) on the first failure."""

import json
import os
import sys
import tempfile
from pathlib import Path

DATA = Path(tempfile.mkdtemp())
os.environ["EB_DATA_DIR"] = str(DATA)
os.environ["ADMIN_TOKEN"] = "test-admin-token"
sys.path.insert(0, str(Path(__file__).parent))

from fastapi.testclient import TestClient  # noqa: E402

import server  # noqa: E402

c = TestClient(server.app, raise_server_exceptions=False)
failures = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(("ok   " if ok else "FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        failures.append(name)


VID = "dryFYIBp5ak"  # a real-looking 11-char id
entry = {"video_id": VID, "title": "t", "channel": "CNN", "thumbnail": None, "view_count": 5,
         "duration": 60, "upload_date": "20260101", "timestamp": 1, "category": "사회"}
body = {"result": {"video_id": VID, "sentence_count": 0, "sentences": [], "idioms": []}, "catalog_entry": entry}

# --- admin gate
check("publish without token -> 403", c.post("/admin/publish", json=body).status_code == 403)
check("publish wrong token -> 403", c.post("/admin/publish", headers={"X-Admin-Token": "nope"}, json=body).status_code == 403)
check("publish ok", c.post("/admin/publish", headers={"X-Admin-Token": "test-admin-token"}, json=body).status_code == 200)
bad = {"result": {**body["result"], "video_id": "../x"}, "catalog_entry": {**entry, "video_id": "../x"}}
check("publish path-like video_id -> 400", c.post("/admin/publish", headers={"X-Admin-Token": "test-admin-token"}, json=bad).status_code == 400)

# --- serving videos
check("GET known video -> 200", c.get(f"/videos/{VID}").status_code == 200)
check("GET unknown video -> 404", c.get("/videos/aaaaaaaaaaa").status_code == 404)
check("catalog lists the published one", [i["video_id"] for i in c.get("/catalog").json()] == [VID])
check("POST known url -> done", c.post("/videos", json={"url": f"https://youtu.be/{VID}"}).json().get("status") == "done")
check("POST watch url -> done", c.post("/videos", json={"url": f"https://www.youtube.com/watch?v={VID}"}).json().get("status") == "done")

# --- path traversal / malformed input (used to read arbitrary *.json and 500)
(DATA / "outside.json").write_text(json.dumps({"leak": "no"}), encoding="utf-8")
r = c.post("/videos", json={"url": "https://youtu.be/../outside"})
check("traversal via youtu.be blocked", r.status_code == 400 and "leak" not in r.text, f"{r.status_code} {r.text[:60]}")
r = c.post("/videos", json={"url": "https://youtu.be/%2e%2e/outside"})
check("encoded traversal blocked", r.status_code == 400 and "leak" not in r.text, f"{r.status_code}")
for url in ["https://www.youtube.com/watch", "https://www.youtube.com/shorts/", "https://youtu.be/",
            "https://evil-youtube.com/watch?v=" + VID, "not a url"]:
    check(f"malformed/foreign url -> 400 ({url[:34]})", c.post("/videos", json={"url": url}).status_code == 400)

# --- hidden channels: gone from /catalog, but the video itself (e.g. a saved bookmark) still opens
ECO = "eeEEeeEEeee"
eco_entry = {**entry, "video_id": ECO, "channel": "The Economist"}
eco_body = {"result": {"video_id": ECO, "sentence_count": 0, "sentences": [], "idioms": []}, "catalog_entry": eco_entry}
check("publish economist video", c.post("/admin/publish", headers={"X-Admin-Token": "test-admin-token"}, json=eco_body).status_code == 200)
listed = [i["video_id"] for i in c.get("/catalog").json()]
check("hidden channel not listed", ECO not in listed, str(listed))
check("hidden channel not listed even when asked for by name", c.get("/catalog", params={"channel": "The Economist"}).json() == [])
check("visible channel still listed", VID in listed)
check("hidden channel's video still opens (bookmarks)", c.get(f"/videos/{ECO}").status_code == 200)

# --- view counts are refreshed by the PC after the first sighting
TOKEN = {"X-Admin-Token": "test-admin-token"}
check("views without token -> 403", c.post("/admin/views", json={"views": {VID: 9}}).status_code == 403)
r = c.post("/admin/views", headers=TOKEN, json={"views": {VID: 12300, "unknownvid1": 5, ECO: -4}})
check("views update known ids only (negative and unknown ignored)", r.status_code == 200 and r.json() == {"updated": 1}, r.text)
check("catalog shows the new count", [i["view_count"] for i in c.get("/catalog").json() if i["video_id"] == VID] == [12300])
check("same counts again change nothing", c.post("/admin/views", headers=TOKEN, json={"views": {VID: 12300}}).json() == {"updated": 0})
check("non-numeric count -> 422", c.post("/admin/views", headers=TOKEN, json={"views": {VID: "lots"}}).status_code == 422)
check("catalog.json stays valid JSON after the update", isinstance(json.loads((DATA / "catalog.json").read_text(encoding="utf-8")), list))

# --- accounts: email verification (mandatory), password reset by code, Google sign-in
import re  # noqa: E402
import sqlite3  # noqa: E402

import auth_db  # noqa: E402

MAIL = DATA / "mails.jsonl"
auth_db.MAIL_DEBUG_LOG = str(MAIL)          # mails are written here instead of being sent


def last_code(to: str) -> str:
    rows = [json.loads(l) for l in MAIL.read_text(encoding="utf-8").splitlines() if l.strip()]
    mine = [r for r in rows if r["to"] == to]
    return re.search(r"\b(\d{6})\b", mine[-1]["body"]).group(1)


def forget_resend_limit(email: str) -> None:
    db = sqlite3.connect(auth_db.DB_PATH)
    db.execute("UPDATE email_codes SET sent_at = '2000-01-01T00:00:00+00:00' WHERE email = ?", (email,))
    db.commit(); db.close()


def signup(email, code, pw="password1", nick="n"):
    return c.post("/auth/signup", json={"email": email, "nickname": nick, "password": pw, "code": code})


check("config: mail on, google off", c.get("/auth/config").json() == {"email_code": True, "google": False})
r = signup("a@b.com", "")
check("signup without a code -> 400 'update the app'", r.status_code == 400 and "업데이트" in r.text, r.text)
check("signup with a code nobody sent -> 400", signup("a@b.com", "123456").status_code == 400)
check("send code: bad address -> 400", c.post("/auth/email/send", json={"email": "nope", "purpose": "signup"}).status_code == 400)
check("send code: bad purpose -> 400", c.post("/auth/email/send", json={"email": "a@b.com", "purpose": "x"}).status_code == 400)
check("send signup code", c.post("/auth/email/send", json={"email": "a@b.com", "purpose": "signup"}).status_code == 200)
check("second send within a minute -> 400", c.post("/auth/email/send", json={"email": "a@b.com", "purpose": "signup"}).status_code == 400)
code = last_code("a@b.com")
check("wrong code -> 400", signup("a@b.com", "000000" if code != "000000" else "111111").status_code == 400)
r = signup("c@d.com", code)
check("a code only works for its own address", r.status_code == 400)
r = signup("a@b.com", code, pw="short")
check("short password rejected (and the code is kept)", r.status_code == 400)
r = signup("a@b.com", code)
j = r.json()
check("signup with the right code", r.status_code == 200 and j.get("token") and j.get("email_verified") is True and j.get("has_password") is True, r.text)
TOKEN_A = j["token"]
check("the code is single use", signup("a@b.com", code, nick="x").status_code == 400)
check("send signup code for a registered address -> 400", c.post("/auth/email/send", json={"email": "a@b.com", "purpose": "signup"}).status_code == 400)
check("login", c.post("/auth/login", json={"email": "a@b.com", "password": "password1"}).status_code == 200)
check("wrong password -> 400", c.post("/auth/login", json={"email": "a@b.com", "password": "wrongwrong"}).status_code == 400)

# five wrong guesses void the code, even for the right one afterwards
c.post("/auth/email/send", json={"email": "e@f.com", "purpose": "signup"})
real = last_code("e@f.com")
wrong = "000000" if real != "000000" else "111111"
for _ in range(5):
    signup("e@f.com", wrong)
check("5 wrong tries void the code", signup("e@f.com", real).status_code == 400)
forget_resend_limit("e@f.com")
c.post("/auth/email/send", json={"email": "e@f.com", "purpose": "signup"})
check("a fresh code works again", signup("e@f.com", last_code("e@f.com")).status_code == 200)

# a mail that cannot be delivered must not leave a code behind that blocks the retry
real_deliver = auth_db._deliver
def broken(*a): raise RuntimeError("smtp down")
auth_db._deliver = broken
check("undeliverable mail -> 400", c.post("/auth/email/send", json={"email": "g@h.com", "purpose": "signup"}).status_code == 400)
auth_db._deliver = real_deliver
check("...and an immediate retry is allowed", c.post("/auth/email/send", json={"email": "g@h.com", "purpose": "signup"}).status_code == 200)

# password reset by code
check("reset code for an unknown address -> 400", c.post("/auth/email/send", json={"email": "zz@zz.com", "purpose": "reset"}).status_code == 400)
check("send reset code", c.post("/auth/email/send", json={"email": "a@b.com", "purpose": "reset"}).status_code == 200)
rc = last_code("a@b.com")
check("reset: wrong code -> 400", c.post("/auth/reset/confirm", json={"email": "a@b.com", "code": "000000" if rc != "000000" else "111111", "password": "newpassword1"}).status_code == 400)
check("reset: short password -> 400", c.post("/auth/reset/confirm", json={"email": "a@b.com", "code": rc, "password": "x"}).status_code == 400)
r = c.post("/auth/reset/confirm", json={"email": "a@b.com", "code": rc, "password": "newpassword1"})
check("reset with the right code signs in", r.status_code == 200 and r.json().get("token"), r.text)
check("old password no longer works", c.post("/auth/login", json={"email": "a@b.com", "password": "password1"}).status_code == 400)
check("new password works", c.post("/auth/login", json={"email": "a@b.com", "password": "newpassword1"}).status_code == 200)
check("other sessions were signed out by the reset", c.get("/auth/me", headers={"Authorization": TOKEN_A}).status_code == 401)

# Google sign-in
auth_db.GOOGLE_CLIENT_ID = "client-1"
check("config: google on", c.get("/auth/config").json()["google"] is True)
claims = {"aud": "client-1", "iss": "https://accounts.google.com", "email_verified": "true", "email": "Gina@Gmail.com", "sub": "g-1", "name": "Gina Kim"}
def tokeninfo(ok): return lambda url: ok
def verify(c_):
    try: auth_db.verify_google_token("t", fetch=tokeninfo(c_)); return True
    except auth_db.AuthError: return False
check("google token: accepted", verify(claims))
check("google token: other client's token rejected", not verify({**claims, "aud": "someone-else"}))
check("google token: bad issuer rejected", not verify({**claims, "iss": "evil.com"}))
check("google token: unverified email rejected", not verify({**claims, "email_verified": "false"}))
check("google token: no subject rejected", not verify({k: v for k, v in claims.items() if k != "sub"}))
real_verify = auth_db.verify_google_token
auth_db.verify_google_token = lambda t, fetch=None: {"t-gina": claims, "t-a": {**claims, "email": "a@b.com", "sub": "g-a", "name": "A"}}[t]
r = c.post("/auth/google", json={"id_token": "t-gina"})
jg = r.json()
check("google: new account (verified, no password, email lower-cased)", r.status_code == 200 and jg["email"] == "gina@gmail.com" and jg["email_verified"] is True and jg["has_password"] is False and jg["nickname"] == "Gina Kim", r.text)
check("google: second sign-in is the same account", c.post("/auth/google", json={"id_token": "t-gina"}).json()["email"] == "gina@gmail.com")
r = c.post("/auth/login", json={"email": "gina@gmail.com", "password": "whatever1"})
check("password login for a Google account says so", r.status_code == 400 and "구글" in r.text, r.text)
r = c.post("/auth/google", json={"id_token": "t-a"})
check("google: an existing email is linked, not duplicated", r.status_code == 200 and r.json()["email"] == "a@b.com" and r.json()["email_verified"] is True)
db = sqlite3.connect(auth_db.DB_PATH); n = db.execute("SELECT COUNT(*) FROM users WHERE email='a@b.com'").fetchone()[0]; db.close()
check("...exactly one a@b.com account", n == 1, str(n))
auth_db.verify_google_token = real_verify

# deleting your own account
check("delete-me without a session -> 401", c.post("/auth/delete-me", json={}).status_code == 401)
tok_a = c.post("/auth/login", json={"email": "a@b.com", "password": "newpassword1"}).json()["token"]
check("delete-me: password account needs the right password", c.post("/auth/delete-me", headers={"Authorization": tok_a}, json={"password": "nope-nope-1"}).status_code == 400)
check("delete-me: password account deleted", c.post("/auth/delete-me", headers={"Authorization": tok_a}, json={"password": "newpassword1"}).status_code == 200)
check("...and gone", c.post("/auth/login", json={"email": "a@b.com", "password": "newpassword1"}).status_code == 400)
auth_db.verify_google_token = lambda t, fetch=None: claims
tok_g = c.post("/auth/google", json={"id_token": "t-gina"}).json()["token"]
check("delete-me: a Google account without a password deletes on the session", c.post("/auth/delete-me", headers={"Authorization": tok_g}, json={}).status_code == 200)
auth_db.verify_google_token = real_verify

# the whole server's daily mail cap
auth_db.MAIL_DAILY_CAP = 0
check("daily mail cap reached -> 400", c.post("/auth/email/send", json={"email": "k@l.com", "purpose": "signup"}).status_code == 400)
auth_db.MAIL_DAILY_CAP = 200
auth_db.MAIL_DEBUG_LOG = ""
check("no mail account configured -> 400, config says so", c.post("/auth/email/send", json={"email": "k@l.com", "purpose": "signup"}).status_code == 400 and c.get("/auth/config").json()["email_code"] is False)
check("health", c.get("/health").json().get("status") == "ok")

print(f"\n{len(failures)} failure(s)" if failures else "\nall checks passed")
sys.exit(1 if failures else 0)
