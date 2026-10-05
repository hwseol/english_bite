"""Daily snapshot of the accounts database (run on the server by englishbite-backup.timer).

Uses SQLite's online-backup API, so the copy is consistent even if someone logs in while it
runs - a plain file copy of a live database isn't. Keeps the newest KEEP snapshots.
"""

import sqlite3
from datetime import date
from pathlib import Path

DB = Path.home() / "english_bite" / "backend" / "translate" / "users.db"
OUT_DIR = Path.home() / "backups"
KEEP = 14


def main() -> None:
    OUT_DIR.mkdir(exist_ok=True)
    target = OUT_DIR / f"users-{date.today().isoformat()}.db"
    src = sqlite3.connect(DB)
    dst = sqlite3.connect(target)
    try:
        src.backup(dst)
    finally:
        dst.close()
        src.close()
    for old in sorted(OUT_DIR.glob("users-*.db"))[:-KEEP]:
        old.unlink()
    print(f"backed up -> {target}")


if __name__ == "__main__":
    main()
