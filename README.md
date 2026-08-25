# MindDeck AI Flashcards — Flask

A production-ready Flask build of MindDeck with note and PDF ingestion, offline flashcard generation, securely locked OpenAI/Gemini generation, SM-2 review scheduling, keyboard shortcuts, local persistence, and JSON import/export.

## Run locally

1. Install Python 3.10 or newer.
2. Open a terminal in this folder.
3. Run `python -m venv .venv`.
4. Activate it and run `pip install -r requirements.txt`.
5. Run `python app.py`.
6. Open `http://127.0.0.1:5000` in Chrome.

## Secure AI setup

Provider API keys are read only from server environment variables and are never accepted from browser requests, rendered into HTML, or committed to GitHub.

Run `python scripts/generate_secrets.py`, then place its two outputs in your hosting provider's encrypted environment settings. The plaintext access code is never stored:

- `AI_ACCESS_CODE_HASH`: a salted scrypt hash of an owner code containing at least 20 characters.
- `AI_SESSION_SECRET`: an independent random signing secret of at least 32 characters.
- `OPENAI_API_KEY`: optional; enables Secure OpenAI.
- `GEMINI_API_KEY`: optional; enables Secure Gemini.
- `OPENAI_MODEL` or `GEMINI_MODEL`: optional model overrides.

Never place secret values in this repository or in a client-side `.env` file. After changing production environment variables, redeploy the project. Offline flashcard generation remains available when online AI is locked.

## Production safeguards

- API keys remain server-only and the server fails closed if any required secret is absent.
- Access codes are verified against a salted scrypt hash, never plaintext.
- Successful unlocks create a signed, `HttpOnly`, `Secure`, `SameSite=Strict` session that expires after 15 minutes.
- Double-submit CSRF tokens, same-origin checks, and JSON-only mutating requests.
- Separate brute-force and generation rate limits.
- Strict request and response size limits
- A restrictive nonce-based Content Security Policy, browser process isolation, anti-framing, HSTS, no-sniff, and no-store responses.
- PDF.js is pinned and self-hosted; the page executes no mutable third-party CDN scripts.
- Imported deck values are schema-checked and rendered through safe DOM APIs rather than HTML injection.
- Generic upstream errors that do not reveal secrets
- Weekly dependency monitoring and a security test workflow on every pull request.

The included `Procfile` and `render.yaml` support standard Gunicorn deployment. Study decks remain in each browser's local storage.
