"""Offline checks for llm_enrich.py / admin_sync.py's failure handling. A fake model stands in for the
router, so nothing here touches the network or needs a key:

    python test_llm_enrich.py

The things that must hold: one model per video; a blocked model restarts the video on the next one
from scratch; nothing is silently skipped; if every model fails, nothing is published."""

import json
import re
import sys
import time

import admin_sync
import idioms
import llm_client
import llm_enrich
import pipeline
from llm_client import Fatal, RateLimited, Transient

failures: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(("ok   " if ok else "FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        failures.append(name)


# --- a fake model ------------------------------------------------------------------------------

sleeps: list[float] = []
time.sleep = lambda s: sleeps.append(s)  # nothing in these tests should really wait

CONFIG = {
    "router": {"base_url": "http://x", "key_file": "unused"},
    "models": [
        {"id": "model-a", "via": "router", "expect_served_by": "model-a", "batch_sentences": 30},
        {"id": "model-b", "via": "router", "expect_served_by": "model-b", "batch_sentences": 30},
        {"id": "gemini-paid", "via": "google", "batch_sentences": 30},
    ],
    "retries_per_batch": 3, "backoff_seconds": [1, 2, 3], "max_wait_on_429_s": 90,
    "cooldown_after_exhaustion_s": 900, "max_idioms_per_batch": 12,
}

calls: list[tuple[str, str]] = []  # (model id, first S number in the prompt)


def make_reply(prompt: str, tag: str, idioms_json=None, drop_t=None, bad=None) -> str:
    t_ids = [int(x) for x in re.findall(r"\bT(\d+)(?: *:|:)", prompt.split("=== INPUT ===", 1)[1])]
    # "S5 = T7: text" lines carry their T id after "= "
    t_ids += [int(x) for x in re.findall(r"= T(\d+):", prompt.split("=== INPUT ===", 1)[1])]
    t_ids = sorted(set(t_ids))
    if drop_t is not None:
        t_ids = [t for t in t_ids if t != drop_t]
    tr = {f"T{t}": f"{tag} 번역 {t}" for t in t_ids}
    if bad == "english":
        tr = {k: "this is still english text here" for k in tr}
    return json.dumps({"translations": tr, "idioms": idioms_json or []}, ensure_ascii=False)


def install_fake(behaviour):
    """behaviour(model_id, prompt, n_call_for_model) -> str | Exception | (str, served_by)"""
    counts: dict[str, int] = {}

    def fake_call(config, model_cfg, prompt):
        mid = model_cfg["id"]
        counts[mid] = counts.get(mid, 0) + 1
        # The first real sentence line ("S31: ..." / "S31 = T40: ..."), not the format explanation
        # above it that also mentions "S5 = T7".
        first_s = re.search(r"^S(\d+)\b", prompt.split("=== INPUT ===", 1)[1], re.M).group(1)
        calls.append((mid, first_s))
        out = behaviour(mid, prompt, counts[mid])
        if isinstance(out, Exception):
            raise out
        if isinstance(out, tuple):
            return out
        served = f"prov/{mid}" if model_cfg["via"] == "router" else f"google/{mid}"
        return out, served

    llm_client.call = fake_call
    llm_enrich._cooling_until.clear()
    calls.clear()
    sleeps.clear()


def video(n_sentences: int, split_one: int | None = None):
    """A video of n sentences (each 5 words); sentence `split_one` is long enough to be split in two."""
    sentences = []
    for i in range(1, n_sentences + 1):
        text = f"This is sentence number {i} today."
        if i == split_one:
            text = "This is a really long sentence that keeps going, and going, and going until it must be split on screen, " * 2
            text = text.strip()
        words = [{"text": w, "start": i * 3 + j * 0.3, "end": i * 3 + j * 0.3 + 0.25} for j, w in enumerate(text.split())]
        sentences.append({"text": text, "start": words[0]["start"], "end": words[-1]["end"], "words": words})
    display, index_map = pipeline.split_sentences_for_display(sentences)
    return sentences, display, index_map


# --- 1. happy path, numbering, batching -------------------------------------------------------
S, D, M = video(70, split_one=5)
install_fake(lambda mid, p, n: make_reply(p, mid))
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("all display lines translated, in order", res.translations == [f"model-a 번역 {t}" for t in range(1, len(D) + 1)])
check("a long sentence became two display pieces", len(M[5]) == 2 and len(D) == 71)
check("70 sentences -> 3 batches of up to 30", [c for c in calls] == [("model-a", "1"), ("model-a", "31"), ("model-a", "61")], str(calls))
check("served model recorded", res.served_by == ["prov/model-a"], str(res.served_by))
check("idioms keyed by ORIGINAL sentence, translation by display piece",
      "S5: This is a really long" in llm_enrich.build_prompt(llm_enrich._build_block(S, D, M, 5, 5)) and
      "  T5:" in llm_enrich.build_prompt(llm_enrich._build_block(S, D, M, 5, 5)) and
      "  T6:" in llm_enrich.build_prompt(llm_enrich._build_block(S, D, M, 5, 5)))
check("un-split sentence is one 'S = T' line", "S6 = T7:" in llm_enrich._build_block(S, D, M, 6, 6))

# --- 2. a bad reply is retried on the same model, not skipped ---------------------------------
S, D, M = video(10)
install_fake(lambda mid, p, n: "sorry, here you go: not json" if n == 1 else make_reply(p, mid))
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("unparseable reply retried, same model finishes", res.model == "model-a" and [c[0] for c in calls] == ["model-a", "model-a"], str(calls))

install_fake(lambda mid, p, n: make_reply(p, mid, drop_t=3) if n == 1 else make_reply(p, mid))
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("a missing subtitle line is retried (never left blank)", res.model == "model-a" and len(calls) == 2 and all(res.translations))

install_fake(lambda mid, p, n: make_reply(p, mid, bad="english") if n == 1 else make_reply(p, mid))
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("English left in place of Korean is rejected and retried", len(calls) == 2 and "번역" in res.translations[0])

# --- 3. a model that keeps failing -> the video restarts on the next model, from sentence 1 ----
S, D, M = video(70)
def a_dies_in_batch_two(mid, p, n):
    if mid == "model-a" and n >= 2:
        return Transient("HTTP 503")
    return make_reply(p, mid)
install_fake(a_dies_in_batch_two)
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("whole video redone by model-b (no model mixing)", res.model == "model-b" and set(res.translations) == {f"model-b 번역 {t}" for t in range(1, 71)} or
      all(t.startswith("model-b") for t in res.translations), str(res.translations[:2]))
check("model-b started again from sentence 1", [c for c in calls if c[0] == "model-b"][0][1] == "1", str(calls))
check("no leftovers from model-a's first batch", all(t.startswith("model-b") for t in res.translations))

# --- 4. rate limits ----------------------------------------------------------------------------
S, D, M = video(10)
install_fake(lambda mid, p, n: RateLimited(3) if n == 1 else make_reply(p, mid))
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("short 429: waits, then the same model succeeds", res.model == "model-a" and any(s >= 3 for s in sleeps), str(sleeps))

install_fake(lambda mid, p, n: RateLimited(5000) if mid == "model-a" else make_reply(p, mid))
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("long 429: next model takes the video", res.model == "model-b")
calls.clear()
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("exhausted model is skipped for the next video (no wasted call)", "model-a" not in [c[0] for c in calls] and res.model == "model-b", str(calls))

# --- 5. the router answered with a different model -----------------------------------------------
install_fake(lambda mid, p, n: (make_reply(p, mid), "prov/some-other-model") if mid == "model-a" else make_reply(p, mid))
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("reply from an unexpected model is never accepted", res.model == "model-b")
check("...and is not retried on the same model", [c[0] for c in calls].count("model-a") == 1, str(calls))

# --- 6. unusable model (bad key) ------------------------------------------------------------------
install_fake(lambda mid, p, n: Fatal("HTTP 401") if mid == "model-a" else make_reply(p, mid))
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("fatal error on a model: next model used", res.model == "model-b")

# --- 7. the paid fallback --------------------------------------------------------------------------
install_fake(lambda mid, p, n: Transient("down") if mid != "gemini-paid" else make_reply(p, mid))
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("free models all failing -> paid Gemini finishes the video", res.model == "gemini-paid" and res.served_by == ["google/gemini-paid"])

# --- 8. everything fails: raise, never return partial or empty results -----------------------------
install_fake(lambda mid, p, n: Transient("down"))
try:
    llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
    check("all models failing raises AllModelsFailed", False)
except llm_enrich.AllModelsFailed as e:
    check("all models failing raises AllModelsFailed", all(m in str(e) for m in ("model-a", "model-b", "gemini-paid")), str(e))

# --- 9. idiom safety nets -------------------------------------------------------------------------
S, D, M = video(10)
S[2]["text"] = "He said the plan went over like a lead balloon with voters."
D[2]["text"] = S[2]["text"]
good = {"sentence": 3, "phrase": "went over like a lead balloon", "note_ko": "계획이 크게 실패했다는 뜻입니다.",
        "example": "The joke went over like a lead balloon at dinner."}
english_note = {"sentence": 3, "phrase": "lead balloon", "note_ko": "It means something that fails badly in front of people.", "example": ""}
not_in_sentence = {"sentence": 4, "phrase": "kick the bucket", "note_ko": "죽는다는 뜻의 표현입니다.", "example": ""}
out_of_batch = {"sentence": 99, "phrase": "lead balloon", "note_ko": "크게 실패한다는 뜻입니다.", "example": ""}
whole_sentence = {"sentence": 3, "phrase": S[2]["text"], "note_ko": "문장 전체입니다.", "example": ""}
install_fake(lambda mid, p, n: make_reply(p, mid, idioms_json=[good, english_note, not_in_sentence, out_of_batch, whole_sentence]))
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("only the genuine idiom survives the filters", [(f["sentence_index"], f["phrase"]) for f in res.idioms] == [(3, "went over like a lead balloon")], str(res.idioms))

# --- 10. end to end through the pipeline step (index remapping, result shape) -------------------------
words = []
t = 0.0
for text in ["The", "Fed", "said", "inflation", "is", "cooling.", "Voters", "were", "not", "convinced."]:
    words.append({"text": text, "start_ms": t * 1000, "end_ms": (t + 0.3) * 1000})
    t += 0.4
install_fake(lambda mid, p, n: make_reply(p, mid))
llm_client.load_config = lambda *a, **k: CONFIG
result = pipeline._process_from_words("abcdefghijk", words)
check("pipeline result keeps the app's shape + records the model",
      set(result) == {"video_id", "sentence_count", "sentences", "idioms", "llm_model"} and result["llm_model"] == "model-a"
      and all("ko" in s and "words" in s for s in result["sentences"]))
install_fake(lambda mid, p, n: Transient("down"))
try:
    pipeline._process_from_words("abcdefghijk", words)
    check("pipeline propagates 'no model could do it' (so nothing is published)", False)
except llm_enrich.AllModelsFailed:
    check("pipeline propagates 'no model could do it' (so nothing is published)", True)

# --- 11. admin_sync: a deferred video is not published, and repeated failures stop the run -------------
published, downloads = [], []
admin_sync._load_admin_token = lambda: "t"
admin_sync.CHANNELS = {"CNN": "u"}
admin_sync.fetch_recent_video_ids = lambda url, n: ["vid00000001", "vid00000002", "vid00000003"]
admin_sync._already_known = lambda vid: False
admin_sync.fetch_video_details = lambda vid: {"timestamp": time.time(), "duration": 60, "title": vid, "thumbnails": []}
admin_sync._ensure_router = lambda: None


def fake_download(vid):
    import tempfile
    from pathlib import Path
    downloads.append(vid)
    d = Path(tempfile.mkdtemp(prefix="ebite_test_"))
    p = d / "audio.webm"
    p.write_bytes(b"x")
    return p


admin_sync._download_audio = fake_download


def failing(token, item, audio):
    raise llm_enrich.AllModelsFailed("모든 모델이 실패했어요")


admin_sync._process_and_publish = failing
try:
    admin_sync.sync()
    check("two LLM failures in a row stop the run", False)
except SystemExit as e:
    check("two LLM failures in a row stop the run", "중단" in str(e), str(e))
check("...and the third video was never even downloaded", downloads == ["vid00000001", "vid00000002"], str(downloads))
check("nothing published while models were failing", published == [])

outcomes = iter([llm_enrich.AllModelsFailed("x"), None, None])
def mixed(token, item, audio):
    o = next(outcomes)
    if o:
        raise o
    published.append(item["video_id"])
admin_sync._process_and_publish = mixed
downloads.clear()
admin_sync.sync()
check("a deferred video does not block the ones after it", published == ["vid00000002", "vid00000003"], str(published))

old_cutoff_hours = admin_sync.SCAN_WINDOW_HOURS
check("scan window is 48h, not 'since midnight'", old_cutoff_hours == 48)
admin_sync.fetch_video_details = lambda vid: {"timestamp": time.time() - 49 * 3600, "duration": 60, "title": vid, "thumbnails": []}
downloads.clear()
admin_sync.sync()
check("a video older than 48h ends the channel scan", downloads == [])
admin_sync.fetch_video_details = lambda vid: {"timestamp": time.time() - 47 * 3600, "duration": 60, "title": vid, "thumbnails": []}
outcomes = iter([None, None, None])
published.clear()
admin_sync.sync()
check("a video from 47h ago (e.g. uploaded late last night) is picked up", len(published) == 3, str(published))

print(f"\n{len(failures)} failure(s)" if failures else "\nall checks passed")
sys.exit(1 if failures else 0)
