import json
import os
import urllib.error
import urllib.parse
import urllib.request

from flask import Flask, jsonify, render_template, request

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 20 * 1024 * 1024


@app.get("/")
def home():
    return render_template("index.html")


def post_json(url: str, payload: dict, headers: dict | None = None) -> dict:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", **(headers or {})},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=45) as response:
        return json.loads(response.read().decode("utf-8"))


@app.post("/api/generate")
def generate():
    body = request.get_json(silent=True) or {}
    provider = str(body.get("provider", "")).lower()
    api_key = str(body.get("apiKey", "")).strip()
    notes = str(body.get("text", "")).strip()

    if provider not in {"openai", "gemini"}:
        return jsonify(error="Select OpenAI or Gemini."), 400
    if not api_key:
        return jsonify(error="An API key is required for AI mode."), 400
    if len(notes) < 20:
        return jsonify(error="Please provide more notes."), 400

    prompt = (
        "Create 12–20 concise study flashcards from these notes. Return ONLY "
        "a JSON array of objects with string keys front and back. No Markdown.\n\n"
        + notes[:18000]
    )

    try:
        if provider == "openai":
            data = post_json(
                "https://api.openai.com/v1/chat/completions",
                {
                    "model": "gpt-4o-mini",
                    "messages": [{"role": "user", "content": prompt}],
                    "temperature": 0.2,
                },
                {"Authorization": f"Bearer {api_key}"},
            )
            raw = data["choices"][0]["message"]["content"]
        else:
            url = (
                "https://generativelanguage.googleapis.com/v1beta/models/"
                "gemini-2.0-flash:generateContent?key="
                + urllib.parse.quote(api_key)
            )
            data = post_json(url, {"contents": [{"parts": [{"text": prompt}]}]})
            raw = data["candidates"][0]["content"]["parts"][0]["text"]

        raw = raw.replace("```json", "").replace("```", "").strip()
        cards = json.loads(raw)
        clean = [
            {"front": str(card["front"]).strip(), "back": str(card["back"]).strip()}
            for card in cards
            if isinstance(card, dict) and card.get("front") and card.get("back")
        ]
        if not clean:
            raise ValueError("The model returned no usable cards.")
        return jsonify(cards=clean)
    except urllib.error.HTTPError as exc:
        return jsonify(error=f"AI provider rejected the request ({exc.code}). Check your key."), 502
    except (KeyError, ValueError, json.JSONDecodeError):
        return jsonify(error="The AI returned an unexpected response. Please try again."), 502
    except Exception:
        app.logger.exception("AI generation failed")
        return jsonify(error="AI generation is temporarily unavailable."), 502


@app.get("/health")
def health():
    return jsonify(status="ok")


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "5000")), debug=False)
