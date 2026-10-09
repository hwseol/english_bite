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

# --- accounts
check("signup", c.post("/auth/signup", json={"email": "a@b.com", "nickname": "n", "password": "password1"}).status_code == 200)
check("short password rejected", c.post("/auth/signup", json={"email": "c@d.com", "nickname": "n", "password": "short"}).status_code == 400)
check("wrong password -> 400", c.post("/auth/login", json={"email": "a@b.com", "password": "wrongwrong"}).status_code == 400)
check("health", c.get("/health").json().get("status") == "ok")

print(f"\n{len(failures)} failure(s)" if failures else "\nall checks passed")
sys.exit(1 if failures else 0)
