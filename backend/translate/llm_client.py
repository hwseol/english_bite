"""Talking to the language models: the local FreeLLMAPI router (OpenAI-style, free providers) and,
as the last resort, Google's Gemini API directly with a paid key.

This module only does transport: one prompt in, (text, who-answered) out, with failures sorted
into the three kinds the caller needs to treat differently:

    RateLimited - quota used up (HTTP 429); carries how long to wait
    Transient   - provider hiccup (5xx, timeout, connection dropped); worth retrying
    Fatal       - this model can't be used right now at all (bad key, unknown model, ...)

Keys are read from files named in llm_config.json (outside the repository), never from the
config itself.
"""

import json
import time
from pathlib import Path

import requests

CONFIG_PATH = Path(__file__).with_name("llm_config.json")
GOOGLE_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


class LLMError(Exception):
    pass


class RateLimited(LLMError):
    def __init__(self, wait_s: float, message: str = ""):
        super().__init__(message or f"rate limited, retry in {wait_s:.0f}s")
        self.wait_s = wait_s


class Transient(LLMError):
    pass


class Fatal(LLMError):
    pass


def load_config(path: Path = CONFIG_PATH) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def read_key(key_file: str) -> str:
    path = Path(key_file).expanduser()
    if not path.exists():
        raise Fatal(f"키 파일이 없어요: {path}")
    key = path.read_text(encoding="utf-8").strip()
    if not key:
        raise Fatal(f"키 파일이 비어 있어요: {path}")
    return key


def _find_key(obj, name: str):
    """First value stored under `name` anywhere in a nested JSON structure."""
    if isinstance(obj, dict):
        if name in obj:
            return obj[name]
        for v in obj.values():
            found = _find_key(v, name)
            if found is not None:
                return found
    elif isinstance(obj, list):
        for v in obj:
            found = _find_key(v, name)
            if found is not None:
                return found
    return None


def retry_after_seconds(resp: requests.Response, default: float = 60.0) -> float:
    """How long a 429 says to wait: the Retry-After header, else the router's retryAtMs
    (an epoch timestamp in milliseconds) in the JSON body, else a default."""
    header = resp.headers.get("Retry-After")
    if header:
        try:
            return max(0.0, float(header))
        except ValueError:
            pass
    try:
        retry_at_ms = _find_key(resp.json(), "retryAtMs")
        if isinstance(retry_at_ms, (int, float)):
            return max(0.0, retry_at_ms / 1000.0 - time.time())
    except ValueError:
        pass
    return default


def _router_call(router_cfg: dict, model_cfg: dict, prompt: str) -> tuple[str, str]:
    key = read_key(router_cfg["key_file"])
    body = {
        "model": model_cfg["id"],
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.2,
        "max_tokens": model_cfg.get("max_tokens", 6000),
        "stream": False,
    }
    try:
        # /v1/chat/completions on purpose: it honours the alias id. (/v1/messages ignores it.)
        resp = requests.post(
            router_cfg["base_url"].rstrip("/") + "/chat/completions",
            json=body,
            headers={"Authorization": f"Bearer {key}"},
            timeout=model_cfg.get("timeout_s", 180),
        )
    except (requests.Timeout, requests.ConnectionError) as e:
        raise Transient(f"라우터 연결 문제: {e.__class__.__name__}") from e

    if resp.status_code == 429:
        raise RateLimited(retry_after_seconds(resp))
    if resp.status_code >= 500:
        raise Transient(f"HTTP {resp.status_code}: {resp.text[:160]}")
    if resp.status_code >= 400:
        raise Fatal(f"HTTP {resp.status_code}: {resp.text[:160]}")
    try:
        text = resp.json()["choices"][0]["message"]["content"] or ""
    except (ValueError, KeyError, IndexError, TypeError) as e:
        raise Transient("응답 형식이 이상해요") from e
    return text, resp.headers.get("X-Routed-Via", "")


def _google_call(model_cfg: dict, prompt: str) -> tuple[str, str]:
    key = read_key(model_cfg["key_file"])
    body = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "temperature": 0.2,
            "maxOutputTokens": model_cfg.get("max_tokens", 8000),
            "responseMimeType": "application/json",
        },
    }
    try:
        resp = requests.post(
            GOOGLE_URL.format(model=model_cfg["id"]),
            json=body,
            headers={"x-goog-api-key": key},
            timeout=model_cfg.get("timeout_s", 120),
        )
    except (requests.Timeout, requests.ConnectionError) as e:
        raise Transient(f"Google 연결 문제: {e.__class__.__name__}") from e

    if resp.status_code == 429:
        raise RateLimited(retry_after_seconds(resp))
    if resp.status_code >= 500:
        raise Transient(f"HTTP {resp.status_code}: {resp.text[:160]}")
    if resp.status_code >= 400:
        raise Fatal(f"HTTP {resp.status_code}: {resp.text[:160]}")
    try:
        parts = resp.json()["candidates"][0]["content"]["parts"]
        text = "".join(p.get("text", "") for p in parts)
    except (ValueError, KeyError, IndexError, TypeError) as e:
        raise Transient("Google 응답 형식이 이상해요") from e
    return text, f"google/{model_cfg['id']}"


_last_call: dict[str, float] = {}


def _pace(model_cfg: dict) -> None:
    """Some providers cap tokens per minute (Groq: 8k), so consecutive calls to such a model are
    spaced out by min_interval_s."""
    gap = model_cfg.get("min_interval_s", 0)
    if gap:
        wait = _last_call.get(model_cfg["id"], 0.0) + gap - time.time()
        if wait > 0:
            time.sleep(wait)


def call(config: dict, model_cfg: dict, prompt: str) -> tuple[str, str]:
    """One prompt to one model. Returns (text, served_by) where served_by is what actually
    answered ("groq/gpt-oss-120b" from the router's X-Routed-Via header)."""
    _pace(model_cfg)
    try:
        if model_cfg.get("via") == "google":
            return _google_call(model_cfg, prompt)
        return _router_call(config["router"], model_cfg, prompt)
    finally:
        _last_call[model_cfg["id"]] = time.time()


def router_alive(config: dict) -> tuple[bool, str]:
    """Is the router up and does our key work? (Used once before a sync run.)"""
    try:
        key = read_key(config["router"]["key_file"])
    except Fatal as e:
        return False, str(e)
    try:
        resp = requests.get(
            config["router"]["base_url"].rstrip("/") + "/models",
            headers={"Authorization": f"Bearer {key}"},
            timeout=8,
        )
    except requests.RequestException as e:
        return False, f"라우터에 연결할 수 없어요 ({e.__class__.__name__})"
    if resp.status_code != 200:
        return False, f"라우터가 HTTP {resp.status_code}로 답했어요"
    return True, "ok"
