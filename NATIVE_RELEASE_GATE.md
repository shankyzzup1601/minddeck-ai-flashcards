# MindDeck Native release gate — HOLD

No APK has been published. Existing MindDeck download URLs, deployed website, and accounts remain unchanged.

Tested application source: `6204f3efe13b96030912420b2161b403187b2608` (31 August 2026).

## Verified

- Clean signed release build succeeds with JDK 17, Android SDK 35 and Gradle 8.11.1.
- Seven release JVM tests pass, no skipped tests.
- Nine backend contract/security tests pass using mocked services; these do not prove live authentication or AI access.
- Debug compilation and Android lint pass in CI.
- APK signature verifies using APK Signature Scheme v2 and a permanent RSA 3072-bit certificate.
- Package: `com.minddeck.nativeapp`; min Android 8; target Android 15.
- Candidate SHA-256: `0e317f80c4f1c355506816f8df3eaf537c53819536d7f424c316711ab39a6d71`.

## Five synthetic device rounds

[CI evidence](https://github.com/shankyzzup1601/minddeck-ai-flashcards/actions/runs/33398782616)

Each round runs five instrumented checks: navigation/activity recreation, timer pause/recreation, account-scoped persistent storage, duplicate focus completion, and encrypted credentials/clear.

| Round | Environment | Result |
| --- | --- | --- |
| 1 | Android 15 Pixel 2 emulator, default settings | Pass — 5/5 instrumented checks |
| 2 | 150% system font size | Pass — 5/5 instrumented checks |
| 3 | Wi-Fi and cellular disabled | Pass — 5/5 instrumented checks |
| 4 | Landscape orientation | Pass — 5/5 instrumented checks |
| 5 | Five explicit force-stop/relaunch cycles after test suite | Pass — 5/5 checks plus 5/5 relaunches |

Device rounds use the debug variant of the same source, not the release-signed binary. Explicit force-stop smoke tests verify successful relaunch, not full low-memory process-death recovery. ActivityScenario does not support Android's “Don't keep activities” setting; the incompatible initial runner was corrected, and its failed rounds are not counted as successful.

All five jobs passed: 25 instrumented test executions, plus five successful process relaunches. Earlier test runner failures and the navigation spacing fix are preserved in Git history. Screenshots/reports are attached to the CI run.

## Still blocks release

- Register production Android Google OAuth using the package and certificate below; configure the matching Web client ID and Supabase provider.
- Apply the separate quota SQL migration, deploy the native API, and verify actual AI provider access.
- Test real Google sign-in, cancellation, account switching, expired/revoked sessions and offline logout.
- Test real AI success and saving, slow/interrupted network, recovery and quota enforcement end to end.
- Install and test the release-signed APK on real hardware, then verify a higher-version in-place signed update preserves cards and settings.
- Exercise keyboard/repeated-tap behavior, multiple Android versions, memory pressure and storage exhaustion; five emulator conditions do not cover every rough situation.
- Continue screen-by-screen checks on release hardware. Initial emulator screenshot review found crowded navigation text at 150% font size; fixed in the tested source above and visually rechecked. Landscape uses scrolling. Any unresolved failure or untested critical path retains HOLD.

## OAuth certificate (public information)

SHA-1: `85:32:FA:60:C3:13:C0:9B:05:86:F0:E1:E6:46:30:07:D5:DF:D3:61`

SHA-256: `AD:24:C4:EC:29:19:72:16:93:60:0C:00:C4:74:A0:D3:CB:90:E9:94:2B:DB:62:E3:0A:5C:95:16:4F:13:B6:E4`

Private signing credentials are not in this repository. Keep their separate backup private and preserve it for future updates.

This is an initial native preview. Cloud deck sync, PDF/image import, native timer notifications, avatars, calendar planner and account deletion are not implemented. It must not be described as feature-complete, production-ready, or guaranteed bug-free.
