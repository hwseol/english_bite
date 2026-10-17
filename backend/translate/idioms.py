"""Rules for picking idioms/expressions worth explaining, and the filters that clean up what the
language model returns.

The model call itself lives in llm_enrich.py (idioms and the Korean subtitles come back from one
request); this module owns what counts as a good idiom (IDIOM_RULES, shown to the model) and the
safety nets applied to every finding afterwards (filter_findings). Those filters were written
against a small local model that often misbehaved - larger models misbehave less, so they should
rarely fire, but they're cheap and the cost of a bad idiom card (wrong, English-only, or a whole
sentence) is a confusing screen for a learner, so they stay.
"""
import re

# Shown to the model as JOB 1. Sentences are referred to by their "S" numbers.
IDIOM_RULES = """Find idioms, fixed expressions, and political/institutional terms in these consecutive
news sentences that a Korean adult learning English would specifically need explained.

Go through the S sentences one by one and be selective - most sentences in ordinary news speech
contain nothing worth flagging, so finding nothing is a completely normal result, not a failure.
Only flag a phrase you're confident this learner genuinely could not understand from the
individual words - not anything merely a bit colorful, formal, or unfamiliar-sounding.

Flag ONLY:
(a) a real idiom or fixed expression whose meaning can't be guessed from the individual words
    (e.g. "go over like a lead balloon", "kick the bucket", "under the weather", "work the
    refs", "punch above their weight"),
(b) a named political/institutional term a Korean reader likely doesn't already know (e.g.
    "the Fed", "filibuster", "Capitol Hill", "the Bureau of Labor Statistics"),
(c) US/UK-specific cultural shorthand that isn't self-explanatory.

Never flag: a person's name, a company/brand/game/place name, a plain number or statistic, a
generic job title, an ordinary descriptive word or vivid verb ("strangled", "grueling",
"terrifying", "gore" are plain English, not idioms), filler words ("you know", "sort of", "I
mean"), or a whole sentence/clause instead of a specific short phrase.

The "phrase" must be the SHORT specific expression itself (a few words), copied verbatim from
the sentence - never most or all of the sentence it came from.

"note_ko" must be written entirely in Korean - not English, not a mix. This is for a Korean
learner studying English, so an English explanation is useless to them. Just give the concrete
meaning directly, in 1-2 sentences. Do NOT pad the note with vague filler commentary instead of
an actual meaning - none of these are acceptable substitutes for saying what the phrase means:
- "한국 학습자는 이해하기 어려울 수 있습니다" (restating why it was picked)
- "직역하기 어렵고" (saying a literal translation is hard, without giving the real one)
- "문맥에 따라 의미가 달라집니다" / "특정 상황을 지칭하는 표현입니다" (vague, says nothing)

"example" must be one new, natural English sentence using the phrase correctly - NOT copied
from the source sentence, a fresh everyday context instead, so the learner sees it used a
second way."""

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


def _significant_words(text: str) -> list:
    return [w for w in re.findall(r"[a-zA-Z']+", text.lower()) if w not in STOPWORDS and len(w) > 1]


# "어렵다" (difficult) is a ㅂ-irregular verb: "어렵" only appears in its dictionary/plain form
# (어렵다, 어렵고) - conjugations like 어려울/어려운/어려워(서) drop the ㅂ and insert 워/러
# instead, so "어렵" alone misses the single most common form the model actually uses. These
# only count as redundant when paired with "한국" - "어렵" etc. can legitimately show up
# elsewhere in a real explanation of what a phrase means.
KOREAN_DIFFICULTY_MARKERS = (
    "어렵", "어려울", "어려운", "어려워",
    "익숙하지", "생소", "낯설", "이해하기", "모를 수", "몰랐",
)
# These are redundant on their own, regardless of whether "한국" is mentioned - vague
# throat-clearing ("literal translation is hard", "it's a context-dependent expression")
# instead of an actual meaning.
VAGUE_FILLER_MARKERS = (
    "직역하기", "직역이", "문맥에 따라", "특정 상황을 지칭",
)


def _is_redundant_sentence(s: str) -> bool:
    if any(marker in s for marker in VAGUE_FILLER_MARKERS):
        return True
    return "한국" in s and any(marker in s for marker in KOREAN_DIFFICULTY_MARKERS)


_REDUNDANT_CLAUSE_KO_RE = re.compile(
    r",\s*[^,.!?]*한국[^,.!?]*(?:" + "|".join(re.escape(m) for m in KOREAN_DIFFICULTY_MARKERS) + r")[^,.!?]*"
)
_REDUNDANT_CLAUSE_VAGUE_RE = re.compile(
    r",\s*[^,.!?]*(?:" + "|".join(re.escape(m) for m in VAGUE_FILLER_MARKERS) + r")[^,.!?]*"
)


def _strip_redundant_commentary(note: str) -> str:
    # Most often this is a clause tacked onto the end of a sentence after a comma ("...의
    # 약자이며, 한국 독자는 이해하기 어려울 수 있습니다.") rather than a separate sentence of
    # its own - cut the clause first. "이며"/"이고" (copula + connective) left dangling right
    # before the cut gets turned into a proper sentence ending instead of a broken half-sentence.
    cleaned = _REDUNDANT_CLAUSE_KO_RE.sub("", note.strip())
    cleaned = _REDUNDANT_CLAUSE_VAGUE_RE.sub("", cleaned)
    cleaned = re.sub(r"이며([.!?])", r"입니다\1", cleaned)
    cleaned = re.sub(r"이고([.!?])", r"입니다\1", cleaned)
    cleaned = re.sub(r"^[,.\s]+", "", cleaned).strip()

    # Whatever's left might still include a whole separate sentence that's pure commentary
    # (e.g. a second sentence restating why this was flagged) - drop that sentence outright.
    # Doing this AFTER the clause cut, not before: on a note that's just one sentence, checking
    # "does a marker appear anywhere in this (only) sentence" would match the sentence
    # containing the now-already-removed clause too and wipe the entire note for nothing.
    sentences = re.split(r"(?<=[.!?])\s+", cleaned)
    kept = [s for s in sentences if not _is_redundant_sentence(s)]
    result = " ".join(kept).strip()
    if result:
        return result
    return cleaned if cleaned else note


def _is_mostly_korean(text: str) -> bool:
    # Despite the prompt insisting on Korean, a model occasionally answers in English -
    # useless to the Korean learner this note is for, so drop it rather than show it.
    hangul = len(re.findall(r"[가-힣]", text))
    latin = len(re.findall(r"[a-zA-Z]", text))
    if hangul + latin == 0:
        return True
    return hangul / (hangul + latin) >= 0.5


def _phrase_appears_in_sentence(phrase: str, sentence: str) -> bool:
    # A model sometimes echoes one of the prompt's example phrases verbatim even when
    # it has nothing to do with the actual sentence. Require most of the phrase's
    # meaningful words to actually occur in the sentence to catch that.
    words = _significant_words(phrase)
    if not words:
        return True
    sentence_words = set(_significant_words(sentence))
    return sum(1 for w in words if w in sentence_words) / len(words) >= 0.5


FILLER_MARKERS = (
    "i mean", "sort of", "such a thing", "you know", "kind of", "any sort",
    "for him", "for her", "great leader", "number one",
)


def _is_plausible_phrase(phrase: str, sentence: str) -> bool:
    # A "phrase" that's most of the whole sentence, or just long, isn't a specific expression -
    # it's the model picking a clause instead of doing its job (a real idiom is a handful of
    # words). Also reject pure numbers/amounts and generic filler the model keeps flagging
    # anyway despite being told not to - a substring check, not exact match, since "sort of
    # big tactic" and "sort of the Russians" slipped through an exact-match version of this.
    phrase_words = _significant_words(phrase)
    if not phrase_words or len(phrase_words) > 6:
        return False
    if re.fullmatch(r"[\d,.\s]+", phrase.strip()):
        return False
    if any(marker in phrase.strip().lower() for marker in FILLER_MARKERS):
        return False
    if re.search(r"\d", phrase) and len(phrase_words) <= 3:
        return False  # a statistic/amount ("90 billion euros"), not an expression
    # The word-count cap above already keeps a "phrase" to a handful of words - this ratio is
    # only meant to catch the case that cap alone misses: an entire short sentence flagged as
    # one "phrase". A short sentence built around one legitimate multi-word idiom (very common
    # in punchy news clips - "went over like a lead balloon" is 5 of a 9-word sentence) was
    # getting rejected here for the same reason a genuine whole-clause mistake would be -
    # 0.65 still catches the latter while no longer punishing a real idiom for being in a
    # short sentence.
    sentence_word_count = max(len(_significant_words(sentence)), 1)
    return len(phrase_words) / sentence_word_count <= 0.65


def filter_findings(raw_items: list, sentences: list, max_items: int) -> list:
    """Applies every check to the idioms a model returned for one batch and keeps the good ones.

    raw_items: dicts with "sentence_index" (1-based, into `sentences`), "phrase", "note_ko",
    "example". sentences: the full list of the video's un-split sentences ({"text": ...})."""
    findings = []
    for item in raw_items[:max_items]:
        if not isinstance(item, dict):
            continue
        idx = item.get("sentence_index")
        if not isinstance(idx, int) or not (1 <= idx <= len(sentences)):
            continue
        note = (item.get("note_ko") or "").strip()
        if not note or any(p in note for p in SELF_CONTRADICTION_MARKERS):
            continue
        if not _is_mostly_korean(note):
            continue
        note = _strip_redundant_commentary(note)
        phrase = item.get("phrase") or ""
        sentence_text = sentences[idx - 1]["text"]
        if not _phrase_appears_in_sentence(phrase, sentence_text):
            continue
        if not _is_plausible_phrase(phrase, sentence_text):
            continue
        example = (item.get("example") or "").strip()
        # A hallucinated/off-topic example is worse than none - only keep it if it
        # actually seems to use the flagged phrase, same check used above for the
        # source sentence itself.
        if not example or not _phrase_appears_in_sentence(phrase, example):
            example = None
        findings.append({"sentence_index": idx, "phrase": phrase, "note_ko": note, "example": example})
    return findings
