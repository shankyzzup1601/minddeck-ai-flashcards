# Security policy

## Supported version

Only the current `main` branch and current Vercel production deployment are supported.

## Reporting a vulnerability

Do not publish API keys, access codes, auth cookies, session tokens, exploit payloads, or personal study data in a public issue. Use GitHub's private vulnerability reporting feature for this repository when available. Revoke and rotate any credential immediately if exposure is suspected.

## Secret-handling rules

- Never commit `.env` files or real secrets.
- Never configure a Supabase secret/service-role key in MindDeck. Use only the project URL and publishable key with user-scoped Row Level Security.
- Provider keys belong only in the hosting provider's encrypted environment settings.
- Store only a salted scrypt access-code hash, never the plaintext code.
- Rotate `AI_SESSION_SECRET` to invalidate every active AI session.
- Keep online providers locked when they are not actively needed.
