"""Idioms + Korean subtitles for one video, from a language model, in a few big requests.

Replaces the old local Gemma (idioms) and NLLB (translation) steps. Each request carries a batch
of consecutive sentences and gets back both answers in one JSON object. The two jobs work on
different units on purpose, exactly as before:

  idioms       -> the original, un-split sentences ("S" numbers) - a phrase needs its full context
  translation  -> the screen-sized pieces those sentences were cut into ("T" numbers)

The rules that matter operationally (see llm_config.json for the model order):

  * One video is processed start to finish by ONE model, so the tone is consistent. If a model
    gets blocked part-way, everything it produced for that video is thrown away and the video
    restarts from the beginning on the next model.
  * Nothing is skipped quietly. A batch that comes back unusable is retried; if it still can't be
    had, the whole model attempt fails; if every model fails, AllModelsFailed is raised and the
    caller must NOT publish the video - it stays unprocessed and the next sync run tries again.
  * A reply that was served by a different model than the one asked for (the router fell over)
    counts as that model being blocked, never as a success.
"""

import json
import re
import time
from dataclasses import dataclass, field

import idioms
import llm_client
from llm_client import Fatal, RateLimited, Transient


class AllModelsFailed(Exception):
    """No configured model could process the video. The video must not be published."""


class _ModelFailed(Exception):
    """This model can't finish this video; the next model should start it over."""


@dataclass
class Enriched:
    translations: list[str]          # one Korean line per display sentence, in order
    idioms: list[dict]               # findings keyed by ORIGINAL sentence index (1-based)
    model: str                       # the configured model id that did the whole video
    served_by: list[str] = field(default_factory=list)  # what the router reported, distinct


KO_RULES = """Translate EVERY T line into Korean subtitles.
- Natural, concise Korean in the plain written news style (~다, ~했다), like a TV news subtitle.
- Keep the meaning exactly. Do not add, drop, merge or split anything: exactly one Korean line
  per T line, even for a short fragment.
- The lines come from speech recognition, so odd spacing or a mis-heard word is possible - translate
  what was clearly meant.
- People, places and organizations: use the usual Korean rendering, or keep the original if there is
  none. Keep numbers and units correct.
- Never repeat a word or phrase over and over."""

PROMPT = """You are helping Korean office workers study English with real news clips. The lines below are
consecutive speech-recognition output. Do TWO jobs and answer with ONE JSON object.

=== JOB 1: IDIOMS ===
{idiom_rules}

=== JOB 2: KOREAN SUBTITLES ===
{ko_rules}

=== INPUT ===
Each "S<number>" is a full sentence (use these numbers for JOB 1). The "T<number>" lines are its
subtitle lines (use these for JOB 2). "S5 = T7" means that sentence is a single subtitle line.

{block}

=== OUTPUT ===
Respond with ONLY this JSON object - no commentary, no code fence:
{"translations": {"T7": "<Korean for T7>", "T8": "<Korean for T8>"}, "idioms": [{"sentence": <S number>, "phrase": "<verbatim from the sentence>", "note_ko": "<Korean explanation>", "example": "<new English sentence>"}]}
"translations" must contain EVERY T number from the input. "idioms" may be an empty list [].
"""


# --- building the request -------------------------------------------------------------------

def _build_block(sentences: list[dict], display_sentences: list[dict], index_map: dict, lo: int, hi: int) -> str:
    """Text for original sentences lo..hi (1-based, inclusive)."""
    lines = []
    for i in range(lo, hi + 1):
        pieces = index_map[i]
        if len(pieces) == 1 and display_sentences[pieces[0] - 1]["text"] == sentences[i - 1]["text"]:
            lines.append(f"S{i} = T{pieces[0]}: {sentences[i - 1]['text']}")
        else:
            lines.append(f"S{i}: {sentences[i - 1]['text']}")
            for t in pieces:
                lines.append(f"  T{t}: {display_sentences[t - 1]['text']}")
    return "\n".join(lines)


def build_prompt(block: str) -> str:
    return (PROMPT.replace("{idiom_rules}", idioms.IDIOM_RULES)
                  .replace("{ko_rules}", KO_RULES)
                  .replace("{block}", block))


# --- reading the reply ----------------------------------------------------------------------

def _extract_json(text: str) -> dict:
    t = re.sub(r"^\s*```(?:json)?\s*|\s*```\s*$", "", text.strip(), flags=re.S)
    start, end = t.find("{"), t.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("no JSON object in the reply")
    obj = json.loads(t[start:end + 1])
    if not isinstance(obj, dict):
        raise ValueError("reply JSON is not an object")
    return obj


def _id_of(key) -> int | None:
    """"T12", "t12", "12" or 12 -> 12."""
    m = re.fullmatch(r"\s*[TtSs]?\s*(\d+)\s*", str(key))
    return int(m.group(1)) if m else None


_REPEAT_RE = re.compile(r"([가-힣A-Za-z0-9]+)(?:\s+\1){4,}")


def _bad_translation(source: str, ko: str) -> str | None:
    ko = ko.strip()
    if not ko:
        return "empty translation"
    if _REPEAT_RE.search(ko):
        return "repeated words"
    if len(re.findall(r"[A-Za-z']+", source)) >= 4 and not re.search(r"[가-힣]", ko):
        return "translation is not Korean"
    if len(ko) > 6 * len(source) + 40:
        return "translation far too long"
    return None


def parse_reply(text: str, expected_t: dict[int, str], lo: int, hi: int) -> tuple[dict[int, str], list[dict]]:
    """Returns ({T number: Korean}, raw idiom dicts keyed "sentence_index") or raises ValueError
    describing what was wrong - which makes the caller ask again."""
    obj = _extract_json(text)

    raw_tr = obj.get("translations")
    translations: dict[int, str] = {}
    if isinstance(raw_tr, dict):
        for k, v in raw_tr.items():
            tid = _id_of(k)
            if tid is not None and isinstance(v, str):
                translations[tid] = v
    elif isinstance(raw_tr, list) and len(raw_tr) == len(expected_t) and all(isinstance(v, str) for v in raw_tr):
        translations = dict(zip(sorted(expected_t), raw_tr))  # a plain ordered list also works
    else:
        raise ValueError("no usable 'translations'")

    missing = [t for t in expected_t if t not in translations]
    if missing:
        raise ValueError(f"missing translations for T{missing[0]}" + (f" (+{len(missing) - 1} more)" if len(missing) > 1 else ""))
    for t, src in expected_t.items():
        problem = _bad_translation(src, translations[t])
        if problem:
            raise ValueError(f"T{t}: {problem}")

    raw_idioms = obj.get("idioms", [])
    findings = []
    if isinstance(raw_idioms, list):
        for item in raw_idioms:
            if not isinstance(item, dict):
                continue
            idx = _id_of(item.get("sentence", item.get("sentence_index", item.get("index"))))
            if idx is None or not (lo <= idx <= hi):
                continue  # a number outside this batch is a hallucination, not a finding
            findings.append({
                "sentence_index": idx,
                "phrase": item.get("phrase") or "",
                "note_ko": item.get("note_ko") or "",
                "example": item.get("example") or "",
            })
    return {t: translations[t].strip() for t in expected_t}, findings


# --- running one model over a whole video ----------------------------------------------------

_cooling_until: dict[str, float] = {}


def _cool_down(model_cfg: dict, seconds: float) -> None:
    _cooling_until[model_cfg["id"]] = time.time() + seconds


def _is_cooling(model_cfg: dict) -> bool:
    return _cooling_until.get(model_cfg["id"], 0.0) > time.time()


def _run_batch(config: dict, model_cfg: dict, prompt: str, expected_t: dict[int, str], lo: int, hi: int, log):
    tries_allowed = config.get("retries_per_batch", 3)
    backoff = config.get("backoff_seconds", [5, 20, 60])
    max_wait = config.get("max_wait_on_429_s", 90)
    tries = waits = 0
    problem = "no attempt made"
    while tries < tries_allowed:
        try:
            text, served = llm_client.call(config, model_cfg, prompt)
        except RateLimited as e:
            if e.wait_s <= max_wait and waits < 4:
                waits += 1
                log(f"    한도 초과 - {e.wait_s:.0f}초 기다립니다")
                time.sleep(e.wait_s + 1)
                continue
            _cool_down(model_cfg, config.get("cooldown_after_exhaustion_s", 900))
            raise _ModelFailed(f"한도 소진 (약 {e.wait_s:.0f}초 뒤 가능)")
        except Fatal as e:
            _cool_down(model_cfg, 3600)
            raise _ModelFailed(f"사용할 수 없음: {e}")
        except Transient as e:
            problem = str(e)
        else:
            expect = model_cfg.get("expect_served_by")
            if expect and expect.lower() not in served.lower():
                # The router fell over to something else - a different voice mid-video. Not retried:
                # asking again just risks the same, and this video should move to the next model.
                raise _ModelFailed(f"다른 모델이 답함: {served or '(알 수 없음)'}")
            try:
                translations, findings = parse_reply(text, expected_t, lo, hi)
                return translations, findings, served
            except (ValueError, json.JSONDecodeError) as e:
                problem = f"응답 문제: {e}"
        tries += 1
        log(f"    다시 시도 ({tries}/{tries_allowed}): {problem}")
        if tries < tries_allowed:
            time.sleep(backoff[min(tries - 1, len(backoff) - 1)])
    raise _ModelFailed(f"{tries_allowed}번 시도했지만 실패: {problem}")


def _enrich_with(config: dict, model_cfg: dict, sentences, display_sentences, index_map, log) -> Enriched:
    per_batch = max(1, int(model_cfg.get("batch_sentences", 30)))
    max_items = config.get("max_idioms_per_batch", 12)
    n = len(sentences)
    translations: dict[int, str] = {}
    findings: list[dict] = []
    served_names: list[str] = []

    for lo in range(1, n + 1, per_batch):
        hi = min(lo + per_batch - 1, n)
        expected_t = {t: display_sentences[t - 1]["text"] for i in range(lo, hi + 1) for t in index_map[i]}
        prompt = build_prompt(_build_block(sentences, display_sentences, index_map, lo, hi))
        log(f"  [{model_cfg['id']}] 문장 {lo}-{hi}/{n}")
        batch_tr, batch_raw, served = _run_batch(config, model_cfg, prompt, expected_t, lo, hi, log)
        translations.update(batch_tr)
        if served and served not in served_names:
            served_names.append(served)
        findings.extend(idioms.filter_findings(batch_raw, sentences, max_items))

    # Same phrase flagged twice in one sentence (a batch boundary can't cause this, a model can).
    seen, unique = set(), []
    for f in findings:
        key = (f["sentence_index"], f["phrase"].lower())
        if key not in seen:
            seen.add(key)
            unique.append(f)

    ordered = [translations[t] for t in range(1, len(display_sentences) + 1)]
    return Enriched(translations=ordered, idioms=unique, model=model_cfg["id"], served_by=served_names)


def enrich(sentences: list[dict], display_sentences: list[dict], index_map: dict, config: dict | None = None, log=print) -> Enriched:
    """Idioms and Korean subtitles for a whole video. Raises AllModelsFailed if no model could do
    it - the caller must then leave the video unpublished."""
    if not sentences:
        return Enriched([], [], "")
    config = config or llm_client.load_config()
    failures = []
    for model_cfg in config["models"]:
        name = model_cfg["id"]
        if _is_cooling(model_cfg):
            failures.append(f"{name}: 한도 소진 대기 중")
            continue
        try:
            return _enrich_with(config, model_cfg, sentences, display_sentences, index_map, log)
        except _ModelFailed as e:
            log(f"  [{name}] 이 영상은 다음 모델로 처음부터 다시 처리합니다 - {e}")
            failures.append(f"{name}: {e}")
    raise AllModelsFailed("모든 모델이 실패했어요 - " + "; ".join(failures))
