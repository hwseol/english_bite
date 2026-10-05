"""Operator tool: summarise the crash reports apps have sent (run on the server).

    python view_crashes.py            # grouped by where it crashed, newest first
    python view_crashes.py --full 3   # full stack traces of the 3 most recent

Reads crashes.jsonl from EB_DATA_DIR (or next to this file), same as server.py.
"""

import collections
import json
import os
import sys
from pathlib import Path

LOG = Path(os.environ.get("EB_DATA_DIR") or Path(__file__).parent) / "crashes.jsonl"


def main() -> int:
    if not LOG.exists():
        print("No crash reports yet.")
        return 0
    entries = [json.loads(line) for line in LOG.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(sys.argv) >= 3 and sys.argv[1] == "--full":
        for e in entries[-int(sys.argv[2]):][::-1]:
            print(f"--- {e['at']}  v{e['app_version']}  {e['device']}  Android {e['android']}")
            print(e["trace"], "\n")
        return 0

    groups = collections.defaultdict(list)
    for e in entries:
        lines = [l.strip() for l in e["trace"].splitlines() if l.strip()]
        # Group by exception line + the first app frame, which is where it actually broke.
        app_frame = next((l for l in lines if "com.mhmh2.englishbite" in l), "")
        groups[(lines[0] if lines else "(empty)", app_frame)].append(e)
    print(f"{len(entries)} report(s), {len(groups)} distinct crash(es)\n")
    for (exc, frame), items in sorted(groups.items(), key=lambda kv: kv[1][-1]["at"], reverse=True):
        versions = sorted({i["app_version"] for i in items})
        devices = sorted({i["device"] for i in items})
        print(f"x{len(items)}  last {items[-1]['at']}  versions {versions}")
        print(f"   {exc[:160]}\n   {frame[:160]}\n   devices: {', '.join(devices[:4])}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
