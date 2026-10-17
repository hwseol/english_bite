"""Local comparison of the new idioms+subtitles step against what is already on the server.

Takes videos the server already has (processed earlier with Gemma + NLLB), reuses their word
timings - so there is no download and no Whisper - runs ONLY the new language-model step on them,
and writes a side-by-side report. Nothing is published unless --publish-to is given explicitly.

    python reprocess_compare.py --pick 4
    python reprocess_compare.py dryFYIBp5ak mXHd2cy1pRE --models gpt-oss-120b,mistral-large-3
    python reprocess_compare.py --pick 3 --publish-to https://dev.184-193-203-68.sslip.io   # later, after approval

--models       force these models (comma separated, ids from llm_config.json); each one is run on every
               video so they can be compared. Default: the normal order, i.e. what production would do.
--publish-to   post the NEW result to that server's /admin/publish (token from EB_ADMIN_TOKEN - the dev
               server has its own). Meant for the dev server only.
Needs the router key file named in llm_config.json for router models.
"""

import argparse
import copy
import datetime
import json
import os
import sys
import time
from pathlib import Path

import requests

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import llm_client
import llm_enrich
import pipeline

PROD = "https://184-193-203-68.sslip.io"
OUT = Path(__file__).parent / "compare_out"


def old_result(video_id: str) -> dict:
    r = requests.get(f"{PROD}/videos/{video_id}", timeout=30)
    r.raise_for_status()
    return r.json()


def words_from(old: dict) -> list[dict]:
    words = []
    for s in old["sentences"]:
        for w in s.get("words") or []:
            words.append({"text": w["text"], "start_ms": w["start"] * 1000, "end_ms": w["end"] * 1000})
    return words


def pick_videos(n: int) -> list[dict]:
    """A spread of recent videos with enough speech to judge (40-150 sentences), one per channel first."""
    catalog = requests.get(f"{PROD}/catalog", timeout=30).json()
    catalog.sort(key=lambda i: i.get("timestamp") or 0, reverse=True)
    chosen, seen_channels = [], set()
    for round_no in range(2):
        for item in catalog:
            if len(chosen) >= n:
                return chosen
            if item in chosen or (round_no == 0 and item["channel"] in seen_channels):
                continue
            try:
                old = old_result(item["video_id"])
            except requests.RequestException:
                continue
            if 40 <= old.get("sentence_count", 0) <= 150 and old.get("idioms") is not None:
                chosen.append(item)
                seen_channels.add(item["channel"])
        if len(chosen) >= n:
            break
    return chosen


def short(text: str, n: int) -> str:
    text = " ".join((text or "").split())
    return text if len(text) <= n else text[: n - 1] + "…"


def report_for(item: dict, old: dict, new: dict, seconds: float) -> str:
    lines = [f"## {item['video_id']} · {item['channel']} · {short(item['title'], 80)}",
             f"- 모델: **{new['llm_model']}**, 처리 시간 {seconds:.0f}초",
             f"- 문장 수: 기존 {old['sentence_count']} / 새 {new['sentence_count']}",
             f"- 관용어: 기존 **{len(old.get('idioms') or [])}개** (Gemma) / 새 **{len(new['idioms'])}개**", ""]

    for label, data in (("기존 (Gemma)", old), ("새 방식", new)):
        lines.append(f"### 관용어 - {label}")
        if not data["idioms"]:
            lines.append("- (없음)")
        for i in data["idioms"]:
            idx = i["sentence_index"]
            src = data["sentences"][idx - 1]["text"] if 1 <= idx <= len(data["sentences"]) else ""
            lines.append(f"- **{i['phrase']}** — {i['note_ko']}")
            lines.append(f"  - 원문: {short(src, 110)}")
            if i.get("example"):
                lines.append(f"  - 예문: {short(i['example'], 110)}")
        lines.append("")

    lines += ["### 번역 비교 (처음 14줄)", "", "| # | 영어 | 기존 (NLLB) | 새 방식 |", "|---|---|---|---|"]
    for n, (o, w) in enumerate(zip(old["sentences"], new["sentences"]), start=1):
        if n > 14:
            break
        lines.append(f"| {n} | {short(w['text'], 70)} | {short(o.get('ko', ''), 60)} | {short(w.get('ko', ''), 60)} |")

    same = sum(1 for o, w in zip(old["sentences"], new["sentences"]) if o["text"] == w["text"])
    non_ko = sum(1 for w in new["sentences"] if not any("가" <= c <= "힣" for c in w.get("ko", "")))
    lines += ["", f"- 영어 문장이 기존과 같은 줄: {same}/{len(new['sentences'])} (다르면 문장 나누기가 달라진 것)",
              f"- 새 번역에서 한글이 없는 줄: {non_ko}", ""]
    return "\n".join(lines)


def publish(server: str, item: dict, result: dict) -> None:
    token = os.environ.get("EB_ADMIN_TOKEN")
    if not token:
        raise SystemExit("EB_ADMIN_TOKEN 환경변수가 필요해요 (dev 서버의 관리자 토큰).")
    r = requests.post(f"{server.rstrip('/')}/admin/publish", headers={"X-Admin-Token": token},
                      json={"result": result, "catalog_entry": item}, timeout=120)
    r.raise_for_status()
    print(f"  [published] {item['video_id']} -> {server}", flush=True)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("video_ids", nargs="*")
    ap.add_argument("--pick", type=int, default=0, help="choose this many recent videos automatically")
    ap.add_argument("--models", default="", help="comma separated model ids to force and compare")
    ap.add_argument("--publish-to", default="", help="ALSO publish the new result to this (dev) server")
    args = ap.parse_args()

    catalog_by_id = {i["video_id"]: i for i in requests.get(f"{PROD}/catalog", timeout=30).json()}
    items = [catalog_by_id[v] for v in args.video_ids if v in catalog_by_id]
    if args.pick:
        items += pick_videos(args.pick)
    if not items:
        print("영상이 없어요 (ID를 주거나 --pick N).")
        return 1

    base = llm_client.load_config()
    runs = [None] if not args.models else [m.strip() for m in args.models.split(",") if m.strip()]
    OUT.mkdir(exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    parts = [f"# 새 방식 비교 리포트 ({stamp})", ""]

    for item in items:
        vid = item["video_id"]
        old = old_result(vid)
        words = words_from(old)
        if not words:
            print(f"[skip] {vid}: 단어 시간 정보가 없는 옛 캐시예요")
            continue
        for forced in runs:
            cfg = copy.deepcopy(base)
            if forced:
                cfg["models"] = [m for m in cfg["models"] if m["id"] == forced]
                if not cfg["models"]:
                    print(f"[skip] 설정에 없는 모델: {forced}")
                    continue
            print(f"[{vid}] 새 방식으로 처리 중 ({forced or '기본 순서'})...", flush=True)
            started = time.time()
            usage_before = copy.deepcopy(llm_client.usage)
            try:
                new = pipeline._process_from_words(vid, words, cfg)
            except llm_enrich.AllModelsFailed as e:
                print(f"  [실패] {e}")
                parts += [f"## {vid} — 실패 ({forced or '기본 순서'})", f"- {e}", ""]
                continue
            seconds = time.time() - started
            (OUT / f"{vid}.{new['llm_model']}.json").write_text(json.dumps(new, ensure_ascii=False, indent=1), encoding="utf-8")
            parts.append(report_for(item, old, new, seconds))
            used = {k: llm_client.usage[new['llm_model']][k] - usage_before.get(new['llm_model'], {}).get(k, 0)
                    for k in ("calls", "prompt", "completion")} if new['llm_model'] in llm_client.usage else {}
            if used:
                parts.append(f"- 토큰: 호출 {used['calls']}번, 입력 {used['prompt']:,} + 출력 {used['completion']:,} = **{used['prompt'] + used['completion']:,}** "
                             f"(문장 {old['sentence_count']}개 기준 {(used['prompt'] + used['completion']) / max(len(old['sentences']), 1):.0f}토큰/문장)\n")
            print(f"  -> 관용어 {len(new['idioms'])}개 (기존 {len(old.get('idioms') or [])}), {seconds:.0f}초"
                  + (f", 토큰 {used['prompt'] + used['completion']:,} (호출 {used['calls']}번)" if used else ""), flush=True)
            if args.publish_to:
                publish(args.publish_to, item, new)

    report = OUT / f"report_{stamp}.md"
    report.write_text("\n".join(parts), encoding="utf-8")
    print(f"\n리포트: {report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
