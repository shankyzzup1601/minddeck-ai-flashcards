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

Set these secrets in your hosting provider's encrypted environment settings:

- `AI_ACCESS_CODE`: a unique access code of at least 12 characters. This locks all online AI requests.
- `OPENAI_API_KEY`: optional; enables Secure OpenAI.
- `GEMINI_API_KEY`: optional; enables Secure Gemini.
- `OPENAI_MODEL` or `GEMINI_MODEL`: optional model overrides.

Never place secret values in this repository or in a client-side `.env` file. After changing production environment variables, redeploy the project. Offline flashcard generation remains available when online AI is locked.

## Production safeguards

- Same-origin checks and JSON-only AI requests
- Best-effort per-IP request throttling
- Strict request and response size limits
- Content Security Policy, anti-framing, HSTS, no-sniff, and no-store API responses
- Generic upstream errors that do not reveal secrets

The included `Procfile` and `render.yaml` support standard Gunicorn deployment. Study decks remain in each browser's local storage.
