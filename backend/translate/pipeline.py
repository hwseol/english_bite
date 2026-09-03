import json
import re
import sys
from urllib.parse import urlparse, parse_qs

import pysbd
from youtube_transcript_api import YouTubeTranscriptApi
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

TRANSLATE_MODEL_NAME = "NHNDQ/nllb-finetuned-en2ko"

_tokenizer = None
_model = None


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


def fetch_caption_cues(video_id: str):
    api = YouTubeTranscriptApi()
    transcript_list = api.list(video_id)
    try:
        transcript = transcript_list.find_manually_created_transcript(["en"])
    except Exception:
        transcript = transcript_list.find_generated_transcript(["en"])
    fetched = transcript.fetch()
    return [
        {"text": snippet.text, "start": snippet.start, "duration": snippet.duration}
        for snippet in fetched
    ]


_segmenter = pysbd.Segmenter(language="en", clean=False)


def cues_to_sentences(cues):
    """Caption cues are ~5s sliding windows that rarely end on a sentence boundary
    (periods land mid-cue). Build one continuous transcript with a char->timestamp
    map from the cues, split that transcript into real sentences with pysbd, then
    look up each sentence's time range from the cues whose characters it spans."""
    full_text = ""
    char_map = []  # (char_start, char_end, cue_start, cue_end)

    for cue in cues:
        text = re.sub(r"\s+", " ", cue["text"]).strip()
        if not text:
            continue
        if full_text and not full_text.endswith(" "):
            full_text += " "
        char_start = len(full_text)
        full_text += text
        char_map.append((char_start, len(full_text), cue["start"], cue["start"] + cue["duration"]))

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

        seg_start = seg_end = None
        for cs, ce, ts, te in char_map:
            if ce > start_char and seg_start is None:
                seg_start = ts
            if cs < end_char:
                seg_end = te
        sentences.append({"text": s, "start": seg_start, "end": seg_end})

    return sentences


def load_translator():
    global _tokenizer, _model
    if _model is None:
        _tokenizer = AutoTokenizer.from_pretrained(TRANSLATE_MODEL_NAME)
        _model = AutoModelForSeq2SeqLM.from_pretrained(TRANSLATE_MODEL_NAME)
        _tokenizer.src_lang = "eng_Latn"
    return _tokenizer, _model


def translate_to_ko(text: str) -> str:
    tokenizer, model = load_translator()
    inputs = tokenizer(text, return_tensors="pt")
    tokens = model.generate(
        **inputs,
        forced_bos_token_id=tokenizer.convert_tokens_to_ids("kor_Hang"),
        max_length=200,
    )
    return tokenizer.batch_decode(tokens, skip_special_tokens=True)[0]


def process_video(url: str):
    video_id = extract_video_id(url)
    cues = fetch_caption_cues(video_id)
    sentences = cues_to_sentences(cues)

    for sentence in sentences:
        sentence["ko"] = translate_to_ko(sentence["text"])

    return {"video_id": video_id, "sentence_count": len(sentences), "sentences": sentences}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python pipeline.py <youtube_url>")
        sys.exit(1)

    result = process_video(sys.argv[1])
    with open("pipeline_result.json", "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"Processed {result['sentence_count']} sentences -> pipeline_result.json")
