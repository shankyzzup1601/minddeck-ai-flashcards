# MindDeck Android APK

This native Android wrapper opens the live MindDeck app at `https://minddeck-ai-flashcards.vercel.app/` in a secure full-screen WebView.

To build locally, install Android SDK Platform 35 and Java 17, then run:

```bash
gradle -p android :app:assembleDebug
```

The GitHub Actions workflow creates an installable debug APK as a build artifact.
