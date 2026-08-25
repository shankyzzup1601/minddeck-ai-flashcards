# MindDeck AI Flashcards — Flask

A production-ready Flask build of MindDeck with note and PDF ingestion, offline flashcard generation, optional OpenAI/Gemini generation, SM-2 review scheduling, keyboard shortcuts, local persistence, and JSON import/export.

## Run locally

1. Install Python 3.10 or newer.
2. Open a terminal in this folder.
3. Run `python -m venv .venv`.
4. Activate it and run `pip install -r requirements.txt`.
5. Run `python app.py`.
6. Open `http://127.0.0.1:5000` in Chrome.

## Production

The included `Procfile` and `render.yaml` support standard Gunicorn deployment. The app does not store API keys or study data on the server. Study decks remain in each browser's local storage.
