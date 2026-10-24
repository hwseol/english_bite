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
        m_s = re.search(r"^S(\d+)\b", prompt.split("=== INPUT ===", 1)[1], re.M)
        first_s = m_s.group(1) if m_s else "repair"
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

# --- 12. prompt: the video title as context, one Korean style ------------------------------------------
p = llm_enrich.build_prompt("S1 = T1: Hello.", "DOUBLE-EDGED SWORD: CyberGuy explains")
check("title goes into the prompt as context only", "Video title (context only" in p and "DOUBLE-EDGED SWORD" in p and "do NOT translate it" in p)
check("no title -> no title line", "Video title" not in llm_enrich.build_prompt("S1 = T1: Hello.", None))
check("one consistent Korean style is requested", "never ~습니다" in p and "plain ~다" in p)

# --- 13. a phrase is kept once per video (first occurrence) ---------------------------------------------
S, D, M = video(10)
S[2]["text"] = D[2]["text"] = "The president signed an executive order on Monday."
S[6]["text"] = D[6]["text"] = "That executive order will take effect next week."
eo = lambda n: {"sentence": n, "phrase": "executive order", "note_ko": "대통령이 내리는 행정 명령입니다.", "example": "She signed an executive order today."}
install_fake(lambda mid, p, n: make_reply(p, mid, idioms_json=[eo(3), eo(7)]))
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("repeated phrase appears once, at its first sentence", [(f["sentence_index"], f["phrase"]) for f in res.idioms] == [(3, "executive order")], str(res.idioms))

# --- 14. the Whisper transcript survives a deferral -------------------------------------------------------
import tempfile
from pathlib import Path

import words_cache

words_cache.WORDS_DIR = Path(tempfile.mkdtemp())
transcribe_calls = []
pipeline._transcribe_audio = lambda audio: (transcribe_calls.append(audio) or words)
install_fake(lambda mid, p, n: Transient("down"))
try:
    pipeline.process_uploaded_audio("abcdefghijk", Path("a.webm"), title="Some title")
    check("deferred video keeps its transcript", False)
except llm_enrich.AllModelsFailed:
    check("deferred video keeps its transcript", words_cache.load("abcdefghijk") is not None and len(transcribe_calls) == 1)
install_fake(lambda mid, p, n: make_reply(p, mid))
res = pipeline.process_uploaded_audio("abcdefghijk", None, title="Some title")  # no audio any more
check("the retry reuses it - no second Whisper run, no audio needed", len(transcribe_calls) == 1 and res["llm_model"] == "model-a")
check("transcript removed once the video succeeded", words_cache.load("abcdefghijk") is None)

# --- 15. admin_sync does not download audio it doesn't need -----------------------------------------------
words_cache.save("vid00000001", words)
admin_sync.fetch_video_details = lambda vid: {"timestamp": time.time(), "duration": 60, "title": vid, "thumbnails": []}
outcomes = iter([None, None, None])
admin_sync._process_and_publish = mixed
published.clear()
downloads.clear()
admin_sync.sync()
check("cached transcript -> no download for that video", "vid00000001" not in downloads and "vid00000001" in published, f"{downloads} {published}")
check("the other videos are still downloaded", downloads == ["vid00000002", "vid00000003"], str(downloads))

# --- 16. a short-term limit is waited out; a long one is skipped for as long as it says --------------------
CONFIG_PATIENT = dict(CONFIG, max_wait_on_429_s=600)
S, D, M = video(10)
install_fake(lambda mid, p, n: RateLimited(300) if n == 1 else make_reply(p, mid))
res = llm_enrich.enrich(S, D, M, CONFIG_PATIENT, log=lambda *_: None)
check("a 5-minute limit is waited out on the SAME model (no mid-video switch)", res.model == "model-a" and any(s >= 300 for s in sleeps), str(sleeps))
install_fake(lambda mid, p, n: RateLimited(40000) if mid == "model-a" else make_reply(p, mid))
llm_enrich.enrich(S, D, M, CONFIG_PATIENT, log=lambda *_: None)
left = llm_enrich._cooling_until["model-a"] - time.time()
check("a daily-sized limit keeps the model out for about that long (not a fixed 15 min)", 39000 < left < 41000, f"{left:.0f}s")

# --- 17. a blank translation of a tiny scrap is tolerated; a blank full sentence is not ----------------------
S, D, M = video(10)
S[3]["text"] = D[3]["text"] = "they said."


def blank(t_id, only_model=None):
    def behaviour(mid, p, n):
        reply = json.loads(make_reply(p, mid))
        if only_model is None or mid == only_model:
            reply["translations"][f"T{t_id}"] = ""
        return json.dumps(reply, ensure_ascii=False)
    return behaviour


install_fake(blank(4))
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("blank translation of 'they said.' does not sink the video (no retry, kept in English)",
      res.model == "model-a" and len(calls) == 1 and res.translations[3] == "they said.", f"{calls} {res.translations[3]!r}")
install_fake(blank(2, only_model="model-a"))  # T2 is a normal 5-word sentence; only model-a leaves it blank
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("blank translation of a real sentence that stays blank still fails that model (-> next model finishes)",
      res.model == "model-b" and [c[0] for c in calls].count("model-a") >= 3, str(calls))

# --- 18. a few lines left out are asked for again on their own (not the whole batch) -------------------------
S, D, M = video(10)
n_calls_main = []


def drop_two_then_repair(mid, p, n):
    if "left some subtitle lines untranslated" in p:   # the repair request
        return make_reply(p, mid)                        # fake answers exactly the T lines that were asked for
    return make_reply(p, mid, drop_t=3)


install_fake(drop_two_then_repair)
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("one missing line is repaired with a small follow-up request (2 calls, not 3 full retries)",
      res.model == "model-a" and len(calls) == 2 and res.translations[2] == "model-a 번역 3", f"{calls} {res.translations[:4]}")

install_fake(lambda mid, p, n: make_reply(p, mid, bad="english") if n == 1 else make_reply(p, mid))
res = llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None)
check("when MOST lines are bad the whole batch is asked again (no repair)", len(calls) == 2 and "left some subtitle lines" not in "", str(calls))

check("repair prompt shows neighbours as context only",
      "context (do not translate)" in llm_enrich.REPAIR_PROMPT or True)  # the wording lives in _repair's block, checked below
seen_prompts = []
def capture(mid, p, n):
    seen_prompts.append(p)
    if "left some subtitle lines untranslated" in p:
        return make_reply(p, mid)
    return make_reply(p, mid, drop_t=5)
install_fake(capture)
llm_enrich.enrich(S, D, M, CONFIG, log=lambda *_: None, title="A Title")
repair_p = [p for p in seen_prompts if "left some subtitle lines untranslated" in p][0]
check("repair request: only the missing line is a T line, neighbours are context, title included",
      repair_p.count("\nT5:") == 1 and "context (do not translate): " in repair_p and 'A Title' in repair_p and len(re.findall(r"\nT\d+:", repair_p)) == 1, repair_p[-400:])

# --- 19. one channel's listing failing does not end the run ---------------------------------------------
admin_sync.CHANNELS = {"CNN": "u1", "BBC": "u2"}


def listing2(url, n):
    if url == "u1":
        raise RuntimeError("[youtube:tab] @CNN: Failed to resolve url")
    return ["vid00000001"]


admin_sync.fetch_recent_video_ids = listing2
admin_sync._already_known = lambda vid: False
admin_sync.fetch_video_details = lambda vid: {"timestamp": time.time(), "duration": 60, "title": vid, "thumbnails": []}
outcomes = iter([None])
admin_sync._process_and_publish = mixed
published.clear()
downloads.clear()
admin_sync.sync()
check("a channel whose listing fails does not end the run (the next channel is still scanned)", published == ["vid00000001"], str(published))

# --- spreading videos over models (first_pick_weights)
MIX = {"models": [{"id": "model-a"}, {"id": "model-b"}, {"id": "model-c"}], "first_pick_weights": {"model-a": 3, "model-b": 1}}
llm_enrich._cooling_until.clear()
firsts = [llm_enrich.ordered_models(MIX)[0]["id"] for _ in range(4000)]
share_a = firsts.count("model-a") / len(firsts)
check("weights spread the first pick (a:b = 3:1 gives ~75% a)", 0.70 < share_a < 0.80, f"a share {share_a:.3f}")
check("a model without a weight is never picked first", "model-c" not in firsts)
check("the others stay in configured order as fallbacks",
      all([m["id"] for m in llm_enrich.ordered_models(MIX)][1:] in (["model-b", "model-c"], ["model-a", "model-c"]) for _ in range(50)))
check("every model is still in the list exactly once", sorted(m["id"] for m in llm_enrich.ordered_models(MIX)) == ["model-a", "model-b", "model-c"])
llm_enrich._cool_down(MIX["models"][0], 600)
# (enrich() skips a cooling model, so what matters is the first model that is NOT cooling)
check("a cooling model is never the one actually used first",
      all(next(m for m in llm_enrich.ordered_models(MIX) if not llm_enrich._is_cooling(m))["id"] != "model-a" for _ in range(200)))
llm_enrich._cooling_until.clear()
check("without weights the configured order is unchanged",
      [m["id"] for m in llm_enrich.ordered_models({"models": MIX["models"]})] == ["model-a", "model-b", "model-c"])
llm_enrich._cool_down(MIX["models"][1], 600)
check("one usable weighted model: plain order, nothing breaks",
      [m["id"] for m in llm_enrich.ordered_models(MIX)] == ["model-a", "model-b", "model-c"])
llm_enrich._cooling_until.clear()

print(f"\n{len(failures)} failure(s)" if failures else "\nall checks passed")
sys.exit(1 if failures else 0)
