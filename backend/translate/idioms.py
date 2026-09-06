"""Pick out a handful of idioms/expressions worth explaining per video, using a local
Gemma3-4B (via Ollama). This is deliberately sparse - at most a few per chunk of sentences,
often zero - not an exhaustive scan of every possible term. Wired into pipeline.process_video()
so every newly-cached video gets an "idioms" field alongside its sentences.
"""
import json
import re
import urllib.request

OLLAMA_URL = "http://127.0.0.1:11434/api/generate"
MODEL = "gemma3:4b"
# Bigger chunks = fewer Ollama round-trips per video (each call has several seconds of fixed
# prompt-processing overhead regardless of chunk size, so 8-sentence chunks meant paying that
# overhead ~15x per video for a feature that finds almost nothing most of the time). Cap scaled
# up proportionally to keep roughly the same density, not the same per-chunk hit rate.
CHUNK_SIZE = 24
MAX_PER_CHUNK = 3

PROMPT_TEMPLATE = f"""You are picking out expressions worth explaining to a Korean adult who
already reads English reasonably well, from this chunk of consecutive news sentences.

Be sparse. Pick AT MOST {MAX_PER_CHUNK} items from this whole chunk - only the ones that would
help this learner the most. It is normal and expected to find NOTHING in a given chunk; do not
force a pick just to have an answer. Only pick something if it is a real idiom or fixed
expression whose meaning can't be guessed from the individual words (e.g. "go over like a lead
balloon", "kick the bucket", "under the weather"), a named political/institutional term a
Korean reader likely doesn't already know (e.g. "the Fed", "filibuster", "Capitol Hill"), or
US/UK-specific cultural shorthand that isn't self-explanatory.

Do NOT pick something just because it sounds formal, is a vivid verb ("strangled", "grueling",
"fell silent" are plain English), a well-known country/region name, a generic government title,
or an ordinary person's name.

Respond ONLY with a JSON array (no other text) of at most {MAX_PER_CHUNK} elements. Each element:
{{"index": <sentence number>, "phrase": "<the specific idiom/term, copied verbatim from the sentence>", "note_ko": "<explanation IN KOREAN (한국어), 1-2 sentences>"}}

note_ko must be written entirely in Korean - not English, not a mix. This is for a Korean
learner studying English, so an English explanation is useless to them.

If nothing in this chunk qualifies, respond with exactly: []

Sentences:
{{sentences_block}}
"""

STOPWORDS = {
    "a", "an", "the", "of", "in", "on", "at", "to", "for", "and", "or", "is",
    "are", "was", "were", "with", "as", "by", "it", "its", "this", "that",
    "these", "those", "be", "been", "from", "has", "have", "had", "not",
    "no", "so", "but", "if", "than", "then", "did", "do", "does",
}

SELF_CONTRADICTION_MARKERS = (
    "필요하지 않", "필요 없", "일반적인 표현", "설명이 불필요",
    "익숙합니다", "익숙할 것", "이미 알고 있", "잘 알려진", "널리 알려진",
    "일반적인 이름", "일반적인 대륙", "일반적인 국가", "일반적인 지명",
)


def call_gemma(prompt: str) -> str:
    # No format="json" - forcing strict JSON mode makes the model give up and return {}
    # on some batches. Free-form output plus regex extraction below works better.
    payload = json.dumps({"model": MODEL, "prompt": prompt, "stream": False}).encode("utf-8")
    req = urllib.request.Request(OLLAMA_URL, data=payload, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as resp:
        return json.loads(resp.read().decode("utf-8"))["response"]


def _parse_json_array(text: str) -> list:
    match = re.search(r"\[.*\]", text.strip(), re.DOTALL)
    if not match:
        return []
    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError:
        return []


def _significant_words(text: str) -> list:
    return [w for w in re.findall(r"[a-zA-Z']+", text.lower()) if w not in STOPWORDS and len(w) > 1]


def _is_mostly_korean(text: str) -> bool:
    # Despite the prompt insisting on Korean, Gemma3-4b occasionally answers in English -
    # useless to the Korean learner this note is for, so drop it rather than show it.
    hangul = len(re.findall(r"[가-힣]", text))
    latin = len(re.findall(r"[a-zA-Z]", text))
    if hangul + latin == 0:
        return True
    return hangul / (hangul + latin) >= 0.5


def _phrase_appears_in_sentence(phrase: str, sentence: str) -> bool:
    # The model sometimes echoes one of the prompt's example phrases verbatim even when
    # it has nothing to do with the actual sentence. Require most of the phrase's
    # meaningful words to actually occur in the sentence to catch that.
    words = _significant_words(phrase)
    if not words:
        return True
    sentence_words = set(_significant_words(sentence))
    return sum(1 for w in words if w in sentence_words) / len(words) >= 0.5


def extract_idioms(sentences: list) -> list:
    findings = []
    for start in range(0, len(sentences), CHUNK_SIZE):
        chunk = sentences[start:start + CHUNK_SIZE]
        numbered = "\n".join(f"{start + i + 1}. {s['text']}" for i, s in enumerate(chunk))
        prompt = PROMPT_TEMPLATE.replace("{sentences_block}", numbered)
        try:
            raw = call_gemma(prompt)
        except Exception:
            continue

        for item in _parse_json_array(raw)[:MAX_PER_CHUNK]:
            idx = item.get("index")
            if not isinstance(idx, int) or not (1 <= idx <= len(sentences)):
                continue
            note = (item.get("note_ko") or "").strip()
            if not note or any(p in note for p in SELF_CONTRADICTION_MARKERS):
                continue
            if not _is_mostly_korean(note):
                continue
            phrase = item.get("phrase") or ""
            if not _phrase_appears_in_sentence(phrase, sentences[idx - 1]["text"]):
                continue
            findings.append({"sentence_index": idx, "phrase": phrase, "note_ko": note})

    return findings
