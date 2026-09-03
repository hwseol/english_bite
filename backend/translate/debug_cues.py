import json
from youtube_transcript_api import YouTubeTranscriptApi

video_id = "vs-Sa7bhyqk"
api = YouTubeTranscriptApi()
transcript_list = api.list(video_id)

available = [(t.language, t.language_code, t.is_generated) for t in transcript_list]
print("Available transcripts:", available)

try:
    transcript = transcript_list.find_manually_created_transcript(["en"])
    print("Using MANUAL transcript")
except Exception as e:
    transcript = transcript_list.find_generated_transcript(["en"])
    print("Using GENERATED transcript:", e)

fetched = transcript.fetch()
cues = [{"text": s.text, "start": s.start, "duration": s.duration} for s in fetched]
print(f"Total raw cues: {len(cues)}")
print("First 15 cues:")
for c in cues[:15]:
    print(json.dumps(c, ensure_ascii=False))
