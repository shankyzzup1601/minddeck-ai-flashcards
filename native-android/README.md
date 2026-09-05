# MindDeck Native — unreleased preview

A separate Kotlin/Jetpack Compose Android client, package `com.minddeck.nativeapp`. It contains no WebView, Trusted Web Activity, browser launcher, or PWA. Existing MindDeck APKs, download URLs, website source, and accounts are not replaced.

## Implemented

- Native onboarding with name, Class 11/12, PCB/PCM/Commerce filtering.
- Home, Library, Focus, Account, AI composer, and Q&A review screens.
- Google Credential Manager account chooser with nonce; the backend exchanges the verified Google ID token through Supabase Auth. OAuth secrets and AI keys never enter the APK.
- Encrypted session persistence with Android Keystore AES-GCM; backup disabled; HTTPS-only networking; redirects disabled.
- SQLite card storage isolated by account, transactional deck creation, manual cards, confirmed deck deletion, and spaced reviews.
- Persisted timer deadline, pause/resume/reset, idempotent completed-session recording.
- Online chapter/notes AI client, one in-flight request, timeouts, expired-session refresh, clear error states. Backend requires server-verified identity and durable daily quotas.

## Not yet release-ready

No APK is published until all five test rounds and the live integration checks pass. Source compilation does not mean login, AI, installation, or upgrades have been verified.

Owner setup required:
1. Register an Android OAuth client for `com.minddeck.nativeapp` and the permanent release certificate's SHA-1/SHA-256 fingerprints. Never use a temporary debug certificate for production.
2. Configure `GOOGLE_WEB_CLIENT_ID` on the native backend with the Google Web client ID also accepted by Supabase Google Auth. Keep nonce validation enabled. Configure the same Google project for the Android and Web clients.
3. Apply `supabase/native-ai-quota.sql` in the existing Supabase project. It creates a separate quota table and an authenticated atomic quota function, without changing existing deck records.
4. Deploy `api/minddeck-native.mjs` alongside existing server functions with `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` (or anon key), and working AI Gateway identity/key. The Android client uses the production `/api/minddeck-native` path. That endpoint does not exist in production until reviewed and deployed.
5. Verify a real Google account signs in and a real AI request produces and saves cards. Existing AI service has returned 503s; new UI alone cannot resolve provider access restrictions.

Build with JDK 17, Android SDK 35, Gradle 8.11.1:

    gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
    gradle :app:connectedDebugAndroidTest

Release signing expects environment variables `MINDDECK_NATIVE_KEYSTORE`, `MINDDECK_NATIVE_STORE_PASSWORD`, `MINDDECK_NATIVE_KEY_PASSWORD`, and alias `minddeck-native`. Release never falls back to debug signing. Preserve the same private key for every future update and increase versionCode; do not check keys into Git. Native code changes require installing an update, but compatible updates should not require uninstalling or wiping data.

## Five required rounds

| Round | Required checks | Current gate |
| --- | --- | --- |
| 1 | Signed install, cold/warm launch, reopen, compatible in-place update | Not verified |
| 2 | Small screen, large text, keyboard, rotation, navigation, repeated taps | Automated tests authored; results must be checked |
| 3 | Actual Google chooser, success/cancel, token expiry, logout, wrong account isolation | Blocked on OAuth setup and real account test |
| 4 | Actual AI success, slow/offline network, provider refusal, quota, double taps | Blocked on backend deployment/configuration and provider access |
| 5 | Cards survive relaunch/update, timer background/process recreation, interrupted work | Automated tests authored; device/update checks outstanding |

The CI workflow runs five synthetic emulator conditions (baseline, 150% text, offline, landscape, and activity destruction) and uploads reports only. It never publishes an APK. These are not substitutes for live account/AI tests.

## Explicit limitations

This initial implementation does not yet include cloud deck sync, PDF/image import, native completion notifications, profile avatars, calendar planner, or account deletion. Cards remain device-local, separated by account. AI accuracy requires textbook checks. Finite tests cannot guarantee zero defects. Do not market this preview as equivalent to every feature of the previous product or as a tested production release.
