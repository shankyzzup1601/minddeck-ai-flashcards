# MindDeck AI Flashcards — Flask

A production-ready Flask build of MindDeck with a responsive glass dashboard and three saved visual themes, note and PDF ingestion, offline flashcard generation, securely locked OpenAI/Gemini generation, email accounts with cross-device deck sync, an offline-resilient focus timer, daily goals and study streak widgets, SM-2 review scheduling, keyboard shortcuts, local persistence, and JSON import/export.

## Run locally

1. Install Python 3.10 or newer.
2. Open a terminal in this folder.
3. Run `python -m venv .venv`.
4. Activate it and run `pip install -r requirements.txt`.
5. Run `python app.py`.
6. Open `http://127.0.0.1:5000` in Chrome.

## Account and cloud-sync setup

MindDeck uses Supabase Auth and Postgres for email/password accounts. Browser requests go only to this Flask server. Access and refresh tokens are stored in `HttpOnly`, `Secure`, `SameSite=Strict` cookies and are never exposed to JavaScript or local storage.

New passwords require at least 12 characters. Signing in validates an existing password as-is, so accounts created under an earlier password policy are not incorrectly blocked by the new-account rule. The sign-in dialog also includes a show/hide control and explains when email confirmation is still required.

1. Create a Supabase project.
2. Open its SQL Editor and run `supabase/schema.sql` once. The migration enables and forces Row Level Security, removes anonymous table access, and gives each signed-in user access only to the row matching their Auth user ID.
3. Add these environment variables to Vercel for Production, Preview, and Development:
   - `SUPABASE_URL`: the exact project URL ending in `.supabase.co`.
   - `SUPABASE_PUBLISHABLE_KEY`: the project's publishable key. A legacy anon key also works as `SUPABASE_ANON_KEY`.
4. Redeploy the project.

Do not configure or expose a Supabase secret/service-role key. MindDeck deliberately uses the signed-in user's token so database Row Level Security remains authoritative. Existing local cards are uploaded automatically when the user signs in and the cloud has no newer deck.

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
- Account passwords are handled by Supabase Auth; auth tokens stay in hardened server-issued cookies.
- Cloud decks are validated on both client and server and isolated per user with forced Postgres Row Level Security.
- Sync uses last-write-wins timestamps, retains an offline local copy, and syncs only normalized deck progress and study statistics; notes and uploaded PDF files never leave the device.
- Generic upstream errors that do not reveal secrets
- Weekly dependency monitoring and a security test workflow on every pull request.

The included `Procfile` and `render.yaml` support standard Gunicorn deployment. Signed-out decks remain in browser local storage; signed-in decks and review progress also sync to the user's protected cloud row.
