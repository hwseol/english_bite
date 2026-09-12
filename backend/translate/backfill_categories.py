"""One-off: classify_category() only runs for videos admin_sync.py uploads going forward -
entries already in catalog.json from before this existed have no "category" field. Run once on
the server to backfill them from their titles.

Usage:
    python backfill_categories.py
"""
from catalog import CATALOG_PATH, classify_category
import json

items = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
updated = 0
for item in items:
    if "category" not in item:
        item["category"] = classify_category(item["title"])
        updated += 1

CATALOG_PATH.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"Backfilled category on {updated} of {len(items)} catalog entries")
