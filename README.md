# MindDeck AI Flashcards — Flask

A production-ready Flask build of MindDeck with a responsive glass dashboard, adaptive SM-2 scheduling, leech rescue decks, Feynman voice comparison, exam-engineered cards, cloze and image-occlusion cards, photo OCR, progressive hints, safe Markdown/LaTeX/code formatting, device TTS, a 12-week review heatmap, Pomodoro deck slicing, Formula Cram, a 48-hour Mistake Notebook, Speed Match, unlisted deck links, curated starter decks, secure OpenAI/Gemini generation, email or Google accounts with cloud sync, and offline-first local persistence.

## Smart Study features

- Four-tier confidence grading feeds the SM-2 schedule; four consecutive `Again` ratings automatically tag a leech for the dedicated Cram & Break Down deck.
- Feynman Mode records up to 15 seconds locally, accepts an editable transcript, and compares its key concepts with the model answer. Recordings are never uploaded or synced.
- Text, extracted PDF text, and photos can generate normal, cloze, or mixed decks. Photo OCR uses browser OCR when supported or the explicitly unlocked server-side AI provider.
- NCERT line-by-line generation targets exact keywords, scientist names, exceptions, and common traps. The manual Exam Card Engine also creates reaction-mechanism carousels, formula/unit/dimension matches, journal-entry dual cards, graph flips, Assertion–Reasoning trainers, and progressive derivations.
- PYQ labels, subject metadata, and Exception & Trap badges stay attached to each card through local save, JSON import/export, unlisted deck links, and signed-in cloud sync.
- Formula Cram filters formulas, constants, units, dimensions, and economic identities into a rapid swipe queue for last-day revision.
- An `Again` rating automatically adds the card to the high-priority Mistake Notebook and guarantees a due time no later than 48 hours; errors can also be logged or resolved manually.
- Image-occlusion assets stay in IndexedDB on the device that created them. Card metadata may sync, but the private image file does not.
- Progressive hints work offline and upgrade to locked AI hints when an AI session is already unlocked.
- Review activity, 30-card focus queues, Speed Match, unlisted no-upload share links, and offline starter packs are built in. Share links are not encrypted; anyone with the URL can import the deck.
- A service worker plus IndexedDB shadow backup keeps the study shell and the latest local deck resilient when connectivity drops.

## Run locally

1. Install Python 3.10 or newer.
2. Open a terminal in this folder.
3. Run `python -m venv .venv`.
4. Activate it and run `pip install -r requirements.txt`.
5. Run `python app.py`.
6. Open `http://127.0.0.1:5000` in Chrome.

## Account and cloud-sync setup

MindDeck uses Supabase Auth and Postgres for email/password and Google accounts. Browser API requests go only to this Flask server. Access and refresh tokens are stored in `HttpOnly`, `Secure`, `SameSite=Strict` cookies and are never exposed to JavaScript or local storage. Google uses an authorization-code + PKCE flow: the short-lived verifier is held in a signed, `HttpOnly`, `SameSite=Lax` transaction cookie only until the cross-site callback returns.

New passwords require at least 12 characters. Signing in validates an existing password as-is, so accounts created under an earlier password policy are not incorrectly blocked by the new-account rule. The sign-in dialog also includes a show/hide control and explains when email confirmation is still required.

1. Create a Supabase project.
2. Open its SQL Editor and run `supabase/schema.sql` once. The migration enables and forces Row Level Security, removes anonymous table access, and gives each signed-in user access only to the row matching their Auth user ID.
3. Add these environment variables to Vercel for Production, Preview, and Development:
   - `SUPABASE_URL`: the exact project URL ending in `.supabase.co`.
   - `SUPABASE_PUBLISHABLE_KEY`: the project's publishable key. A legacy anon key also works as `SUPABASE_ANON_KEY`.
   - `OAUTH_SESSION_SECRET`: a random signing secret of at least 32 characters. If omitted, an already configured `AI_SESSION_SECRET` is used with OAuth-specific HMAC domain separation.
   - `PUBLIC_APP_URL`: optional but recommended for the Production environment; use the exact origin with no trailing path, such as `https://minddeck-ai-flashcards.vercel.app`. Omit it from Preview, or give each environment its own exact preview origin.
4. Redeploy the project.

### Google Sign-In setup

The app automatically reveals **Continue with Google** only when the Google provider is enabled in Supabase and a strong server signing secret is available.

1. In Google Auth Platform, create a **Web application** OAuth client.
2. Add `https://minddeck-ai-flashcards.vercel.app` under **Authorized JavaScript origins**. Add only your exact local origin while developing.
3. Under **Authorized redirect URIs**, add the Supabase callback shown on the project's Google provider page. It has the form `https://<project-ref>.supabase.co/auth/v1/callback` — this is intentionally the Supabase URL, not the MindDeck callback.
4. Configure Google's required `openid`, email, and profile scopes, then place the Google client ID and secret in **Supabase Dashboard → Authentication → Providers → Google** and enable the provider. Never place the Google client secret in Vercel or this repository.
5. In **Supabase Dashboard → Authentication → URL Configuration → Redirect URLs**, add `https://minddeck-ai-flashcards.vercel.app/api/auth/google/callback` exactly.
6. Redeploy MindDeck after adding `OAUTH_SESSION_SECRET` or confirm that the existing `AI_SESSION_SECRET` is at least 32 characters.

For local development, allow `http://127.0.0.1:5000/api/auth/google/callback` in Supabase and use the matching local origin. Production should stay HTTPS-only.

Do not configure or expose a Supabase secret/service-role key. MindDeck deliberately uses the signed-in user's token so database Row Level Security remains authoritative. Existing local cards are uploaded automatically when the user signs in and the cloud has no newer deck.

## Secure AI setup

Provider API keys are read only from server environment variables and are never accepted from browser requests, rendered into HTML, or committed to GitHub.

Run `python scripts/generate_secrets.py`, then place its outputs in your hosting provider's encrypted environment settings. The plaintext access code is never stored:

- `AI_ACCESS_CODE_HASH`: a salted scrypt hash of an owner code containing at least 20 characters.
- `AI_SESSION_SECRET`: an independent random signing secret of at least 32 characters.
- `OAUTH_SESSION_SECRET`: a separate random signing secret for short-lived Google OAuth transactions.
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
- Strict request and response size limits.
- A restrictive nonce-based Content Security Policy, browser process isolation, anti-framing, HSTS, no-sniff, and no-store responses.
- PDF.js is pinned and self-hosted; the page executes no mutable third-party CDN scripts.
- Imported deck values are schema-checked and rendered through safe DOM APIs rather than HTML injection.
- Account passwords and Google identity checks are handled by Supabase Auth; auth tokens stay in hardened server-issued cookies. The Google PKCE verifier is signed, browser-bound, expires after 10 minutes, and is deleted on both successful and failed callbacks.
- Cloud decks are validated on both client and server and isolated per user with forced Postgres Row Level Security.
- Sync uses last-write-wins timestamps, retains an offline IndexedDB copy, and syncs only normalized deck progress and study statistics. Original notes, PDF files, voice recordings, and image-occlusion files are never placed in cloud deck storage.
- Photo content is sent only after the user explicitly selects a securely unlocked AI provider; MIME type, magic bytes, and size are validated first.
- Generic upstream errors that do not reveal secrets.
- Weekly dependency monitoring and a security test workflow on every pull request.

The included `Procfile` and `render.yaml` support standard Gunicorn deployment. Signed-out decks remain in browser local storage; signed-in decks and review progress also sync to the user's protected cloud row.
