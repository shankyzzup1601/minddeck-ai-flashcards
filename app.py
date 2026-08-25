import hmac
import json
import os
import secrets
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict, deque
from urllib.parse import urlparse

from flask import Flask, g, jsonify, render_template, request

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 256 * 1024

MAX_NOTES_CHARS = 18_000
MAX_PROVIDER_RESPONSE_BYTES = 1_000_000
RATE_LIMIT_REQUESTS = 10
RATE_LIMIT_WINDOW_SECONDS = 60

_rate_buckets: dict[str, deque[float]] = defaultdict(deque)
_rate_lock = threading.Lock()


def configured_key(provider: str) -> str:
    """Read provider secrets from the server environment for every request."""
    variable = "OPENAI_API_KEY" if provider == "openai" else "GEMINI_API_KEY"
    return os.environ.get(variable, "").strip()


def access_code() -> str:
    return os.environ.get("AI_ACCESS_CODE", "").strip()


def provider_ready(provider: str) -> bool:
    # A separate access code prevents an accidental unrestricted AI proxy.
    return bool(configured_key(provider) and len(access_code()) >= 12)


@app.get("/")
def home():
    g.csp_nonce = secrets.token_urlsafe(18)
    return render_template("index.html", csp_nonce=g.csp_nonce)


@app.get("/api/config")
def api_config():
    return jsonify(
        providers={
            "openai": provider_ready("openai"),
            "gemini": provider_ready("gemini"),
        },
        accessRequired=True,
    )


def post_json(url: str, payload: dict, headers: dict | None = None) -> dict:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", **(headers or {})},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=45) as response:
        raw = response.read(MAX_PROVIDER_RESPONSE_BYTES + 1)
        if len(raw) > MAX_PROVIDER_RESPONSE_BYTES:
            raise ValueError("Provider response was too large.")
        return json.loads(raw.decode("utf-8"))


def same_origin_request() -> bool:
    if request.headers.get("Sec-Fetch-Site", "").lower() == "cross-site":
        return False

    origin = request.headers.get("Origin")
    if not origin:
        return True

    parsed = urlparse(origin)
    return parsed.scheme in {"http", "https"} and parsed.netloc == request.host


def rate_limit_ok() -> tuple[bool, int]:
    forwarded = request.headers.get("X-Forwarded-For", "")
    client = (forwarded.split(",", 1)[0].strip() or request.remote_addr or "unknown")[:64]
    now = time.monotonic()
    cutoff = now - RATE_LIMIT_WINDOW_SECONDS

    with _rate_lock:
        bucket = _rate_buckets[client]
        while bucket and bucket[0] <= cutoff:
            bucket.popleft()
        if len(bucket) >= RATE_LIMIT_REQUESTS:
            retry_after = max(1, int(RATE_LIMIT_WINDOW_SECONDS - (now - bucket[0])))
            return False, retry_after
        bucket.append(now)

        # Keep this best-effort serverless limiter bounded.
        if len(_rate_buckets) > 10_000:
            stale = [key for key, values in _rate_buckets.items() if not values or values[-1] <= cutoff]
            for key in stale:
                _rate_buckets.pop(key, None)

    return True, 0


def parse_cards(raw: str) -> list[dict[str, str]]:
    cleaned = raw.replace("```json", "").replace("```", "").strip()
    parsed = json.loads(cleaned)
    cards = parsed.get("cards", []) if isinstance(parsed, dict) else parsed
    if not isinstance(cards, list):
        raise ValueError("Invalid card collection.")

    result = []
    for card in cards[:20]:
        if not isinstance(card, dict):
            continue
        front = str(card.get("front", "")).strip()[:500]
        back = str(card.get("back", "")).strip()[:2_000]
        if front and back:
            result.append({"front": front, "back": back})

    if not result:
        raise ValueError("The model returned no usable cards.")
    return result


@app.post("/api/generate")
def generate():
    if not same_origin_request():
        return jsonify(error="Cross-site requests are blocked."), 403
    if not request.is_json:
        return jsonify(error="A JSON request is required."), 415

    allowed, retry_after = rate_limit_ok()
    if not allowed:
        response = jsonify(error="Too many AI requests. Please wait a minute.")
        response.status_code = 429
        response.headers["Retry-After"] = str(retry_after)
        return response

    body = request.get_json(silent=True) or {}
    provider = str(body.get("provider", "")).lower().strip()
    notes = str(body.get("text", "")).strip()
    submitted_code = str(body.get("accessCode", ""))

    # Provider API keys are accepted only from server environment variables.
    if "apiKey" in body:
        return jsonify(error="API keys are accepted only through server configuration."), 400
    if provider not in {"openai", "gemini"}:
        return jsonify(error="Select a supported AI provider."), 400
    if not provider_ready(provider):
        return jsonify(error="This AI provider is securely locked by the owner."), 503
    if not hmac.compare_digest(submitted_code, access_code()):
        return jsonify(error="The AI access code is incorrect."), 401
    if len(notes) < 20:
        return jsonify(error="Please provide more notes."), 400
    if len(notes) > MAX_NOTES_CHARS:
        return jsonify(error=f"Notes must be under {MAX_NOTES_CHARS:,} characters."), 413

    prompt = (
        "Create 12-20 concise study flashcards from the notes below. "
        "Return only valid JSON as an object with a cards array. Each card must "
        "have string fields front and back. Do not include Markdown.\n\n"
        + notes
    )

    try:
        if provider == "openai":
            data = post_json(
                "https://api.openai.com/v1/chat/completions",
                {
                    "model": os.environ.get("OPENAI_MODEL", "gpt-4o-mini"),
                    "messages": [{"role": "user", "content": prompt}],
                    "temperature": 0.2,
                    "response_format": {"type": "json_object"},
                },
                {"Authorization": f"Bearer {configured_key('openai')}"},
            )
            raw = data["choices"][0]["message"]["content"]
        else:
            data = post_json(
                "https://generativelanguage.googleapis.com/v1beta/models/"
                + os.environ.get("GEMINI_MODEL", "gemini-2.0-flash")
                + ":generateContent",
                {
                    "contents": [{"parts": [{"text": prompt}]}],
                    "generationConfig": {"responseMimeType": "application/json"},
                },
                {"x-goog-api-key": configured_key("gemini")},
            )
            raw = data["candidates"][0]["content"]["parts"][0]["text"]

        return jsonify(cards=parse_cards(raw))
    except urllib.error.HTTPError as exc:
        app.logger.warning("AI provider request failed with status %s", exc.code)
        return jsonify(error="The AI provider rejected the request."), 502
    except (KeyError, ValueError, TypeError, json.JSONDecodeError):
        return jsonify(error="The AI returned an unexpected response. Please try again."), 502
    except Exception:
        app.logger.exception("AI generation failed")
        return jsonify(error="AI generation is temporarily unavailable."), 502


@app.get("/health")
def health():
    return jsonify(status="ok")


@app.after_request
def secure_response(response):
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
    response.headers["Cross-Origin-Opener-Policy"] = "same-origin"
    response.headers["Cross-Origin-Resource-Policy"] = "same-origin"

    nonce = getattr(g, "csp_nonce", "")
    nonce_source = f" 'nonce-{nonce}'" if nonce else ""
    https_request = request.is_secure or request.headers.get("X-Forwarded-Proto") == "https"
    upgrade_directive = "upgrade-insecure-requests" if https_request else ""
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; "
        f"script-src 'self'{nonce_source} https://unpkg.com https://cdnjs.cloudflare.com; "
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
        "font-src 'self' https://fonts.gstatic.com; "
        "img-src 'self' data:; connect-src 'self'; "
        "worker-src 'self' blob: https://cdnjs.cloudflare.com; "
        "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'; "
        + upgrade_directive
    )

    if request.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store, max-age=0"
    if https_request:
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
    return response


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "5000")), debug=False)
