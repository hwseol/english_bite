from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

MODEL_NAME = "NHNDQ/nllb-finetuned-en2ko"

print(f"Loading {MODEL_NAME} ...")
tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
model = AutoModelForSeq2SeqLM.from_pretrained(MODEL_NAME)

test_sentences = [
    "The Federal Reserve raised interest rates again on Wednesday, citing persistent inflation pressures.",
    "Officials said the storm is expected to make landfall early Friday morning.",
    "The company's shares fell more than 10 percent after it missed earnings expectations.",
    "Let's take a look at what's coming up on the show tonight.",
]

tokenizer.src_lang = "eng_Latn"
for sentence in test_sentences:
    inputs = tokenizer(sentence, return_tensors="pt")
    translated_tokens = model.generate(
        **inputs,
        forced_bos_token_id=tokenizer.convert_tokens_to_ids("kor_Hang"),
        max_length=128,
    )
    result = tokenizer.batch_decode(translated_tokens, skip_special_tokens=True)[0]
    print(f"EN: {sentence}")
    print(f"KO: {result}")
    print()
