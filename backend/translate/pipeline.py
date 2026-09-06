import json
import re
import shutil
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlparse, parse_qs

import pysbd
import torch
import yt_dlp
from faster_whisper import WhisperModel
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

from idioms import extract_idioms

TRANSLATE_MODEL_NAME = "NHNDQ/nllb-finetuned-en2ko"
WHISPER_MODEL_NAME = "small"

_tokenizer = None
_model = None
_whisper = None


class UserFacingError(Exception):
    """Raised for failures worth showing the user a specific, actionable message for,
    as opposed to an unexpected bug that should surface as a generic server error."""


def extract_video_id(url: str) -> str:
    parsed = urlparse(url)
    if parsed.hostname in ("youtu.be",):
        return parsed.path.lstrip("/")
    if parsed.hostname and "youtube.com" in parsed.hostname:
        if parsed.path == "/watch":
            return parse_qs(parsed.query)["v"][0]
        if parsed.path.startswith("/shorts/"):
            return parsed.path.split("/")[2]
    raise ValueError(f"Could not parse a video ID from URL: {url}")


def load_whisper() -> WhisperModel:
    global _whisper
    if _whisper is None:
        # int8 on CPU (no GPU on this machine) - runs the "small" model at roughly 5x real
        # time, so a ~15min video transcribes in ~3min. Kept as a long-lived global like the
        # translator below rather than reloaded per video.
        _whisper = WhisperModel(WHISPER_MODEL_NAME, device="cpu", compute_type="int8")
    return _whisper


def _download_audio(video_id: str) -> Path:
    tmp_dir = Path(tempfile.mkdtemp(prefix="ebite_audio_"))
    opts = {
        "format": "bestaudio/best",
        "outtmpl": str(tmp_dir / "audio.%(ext)s"),
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
    }
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.download([f"https://www.youtube.com/watch?v={video_id}"])
    except Exception as e:
        shutil.rmtree(tmp_dir, ignore_errors=True)
        raise UserFacingError("이 영상을 찾을 수 없어요. 비공개 영상이거나 삭제된 영상일 수 있어요.") from e

    files = list(tmp_dir.glob("audio.*"))
    if not files:
        shutil.rmtree(tmp_dir, ignore_errors=True)
        raise UserFacingError("이 영상의 오디오를 가져올 수 없었어요. 다른 영상을 시도해주세요.")
    return files[0]


def fetch_caption_words(video_id: str):
    # Previously read YouTube's own caption timing (first the plain cue-level transcript, later
    # its json3 format for per-word offsets) - but that's only as good as YouTube's own ASR
    # alignment, which turned out fine for slow, clearly-enunciated speech and badly wrong for
    # normal-paced interviews and panel discussions (confirmed by directly comparing both against
    # the actual audio). Transcribing the audio ourselves with Whisper - a well-established local
    # ASR model - gives real word-level timestamps derived directly from the audio instead of
    # trusting a third party's alignment we can't inspect or fix.
    audio_path = _download_audio(video_id)
    try:
        model = load_whisper()
        segments, _ = model.transcribe(str(audio_path), word_timestamps=True, language="en")
        words = []
        for segment in segments:
            for w in segment.words:
                text = w.word.strip()
                if text:
                    # w.start/w.end come back as numpy float64, not a plain float - json.dump
                    # chokes on that.
                    words.append({"text": text, "start_ms": float(w.start) * 1000, "end_ms": float(w.end) * 1000})
        if not words:
            raise UserFacingError("이 영상에서 음성을 인식하지 못했어요. 다른 영상을 시도해주세요.")
        return words
    finally:
        shutil.rmtree(audio_path.parent, ignore_errors=True)


_segmenter = pysbd.Segmenter(language="en", clean=False)


def words_to_sentences(words):
    """Build one continuous transcript by joining every word, with a parallel char-range map
    back to each word's real timestamp. Splitting into sentences this way gives each sentence's
    span, and each word's karaoke highlight time within it, directly from the source timing -
    no interpolation needed now that fetch_caption_words already resolved per-word timestamps."""
    full_text = ""
    char_map = []  # (char_start, char_end), index-aligned with `words`

    for w in words:
        text = re.sub(r"\s+", " ", w["text"]).strip()
        if not text:
            continue
        if full_text and not full_text.endswith(" "):
            full_text += " "
        char_start = len(full_text)
        full_text += text
        char_map.append((char_start, len(full_text)))

    sentences = []
    pos = 0
    for raw in _segmenter.segment(full_text):
        s = raw.strip()
        if not s:
            continue
        idx = full_text.find(s, pos)
        if idx == -1:
            idx = full_text.find(s)
        start_char, end_char = idx, idx + len(s)
        pos = end_char

        sentence_words = [
            {"text": words[i]["text"], "start": words[i]["start_ms"] / 1000, "end": words[i]["end_ms"] / 1000}
            for i, (cs, ce) in enumerate(char_map)
            if ce > start_char and cs < end_char
        ]
        if not sentence_words:
            continue
        sentences.append({
            "text": s,
            "start": sentence_words[0]["start"],
            "end": sentence_words[-1]["end"],
            "words": sentence_words,
        })

    return [piece for sentence in sentences for piece in _split_long_sentence(sentence)]


MAX_SENTENCE_CHARS = 110  # a sentence longer than this on screen forces the subtitle font
                          # down to its smallest tier - split it into shorter, still fully
                          # accurately-timed pieces instead (real per-word timestamps make this
                          # exact, not a guess).


def _split_long_sentence(sentence: dict) -> list[dict]:
    words = sentence["words"]
    if len(sentence["text"]) <= MAX_SENTENCE_CHARS or len(words) < 4:
        return [sentence]

    mid = len(words) // 2
    # Prefer cutting right after a comma/semicolon/colon near the middle - reads more
    # naturally than an arbitrary word-count split.
    punct_indices = [i for i, w in enumerate(words) if w["text"].rstrip().endswith((",", ";", ":"))]
    split_at = min(punct_indices, key=lambda i: abs(i - mid)) + 1 if punct_indices else mid
    split_at = max(1, min(split_at, len(words) - 1))

    pieces = []
    for chunk in (words[:split_at], words[split_at:]):
        pieces.append({
            "text": " ".join(w["text"] for w in chunk),
            "start": chunk[0]["start"],
            "end": chunk[-1]["end"],
            "words": chunk,
        })
    return [p for half in pieces for p in _split_long_sentence(half)]


def load_translator():
    global _tokenizer, _model
    if _model is None:
        _tokenizer = AutoTokenizer.from_pretrained(TRANSLATE_MODEL_NAME)
        # bfloat16 halves the resident memory (~1.4GB vs ~2.9GB in float32) with
        # no meaningful quality loss, which matters when this runs alongside
        # Android Studio/the emulator on the same machine.
        _model = AutoModelForSeq2SeqLM.from_pretrained(TRANSLATE_MODEL_NAME, torch_dtype=torch.bfloat16)
        _tokenizer.src_lang = "eng_Latn"
    return _tokenizer, _model


def translate_to_ko(text: str) -> str:
    return translate_batch([text])[0]


def translate_batch(texts: list[str], batch_size: int = 16) -> list[str]:
    """Batching multiple sentences per generate() call only pays off if sentences
    of similar length are grouped together - otherwise every sequence in a batch
    gets padded up to the longest one and most of that compute is wasted on
    padding. Sort by length into batches, translate, then restore original order."""
    tokenizer, model = load_translator()
    order = sorted(range(len(texts)), key=lambda i: len(texts[i]))
    results = [None] * len(texts)

    for i in range(0, len(order), batch_size):
        batch_indices = order[i:i + batch_size]
        chunk = [texts[j] for j in batch_indices]
        inputs = tokenizer(chunk, return_tensors="pt", padding=True, truncation=True, max_length=200)
        with torch.no_grad():
            tokens = model.generate(
                **inputs,
                forced_bos_token_id=tokenizer.convert_tokens_to_ids("kor_Hang"),
                max_length=200,
            )
        for j, decoded in zip(batch_indices, tokenizer.batch_decode(tokens, skip_special_tokens=True)):
            results[j] = decoded

    return results


def process_video(url: str):
    video_id = extract_video_id(url)
    words = fetch_caption_words(video_id)
    sentences = words_to_sentences(words)

    translations = translate_batch([s["text"] for s in sentences])
    for sentence, ko in zip(sentences, translations):
        sentence["ko"] = ko

    idioms = extract_idioms(sentences)

    return {
        "video_id": video_id,
        "sentence_count": len(sentences),
        "sentences": sentences,
        "idioms": idioms,
    }


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python pipeline.py <youtube_url>")
        sys.exit(1)

    result = process_video(sys.argv[1])
    with open("pipeline_result.json", "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"Processed {result['sentence_count']} sentences -> pipeline_result.json")
