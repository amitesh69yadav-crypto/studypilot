# StudyPilot

Android study planner for university students: timetable with exact alarms, topic tracking with
spaced repetition, weekend revision quizzes, an AI tutor, and importing shared AI chats as study notes.

Kotlin, Jetpack Compose, Room. Gemini over REST for AI; Firebase Auth + Firestore for optional cloud backup.

## Build

Requirements: JDK 17+, Android SDK 36.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit + Robolectric tests, including a smoke test that renders every screen
```

Files that are not in the repo:

| File | Needed for | Without it |
| --- | --- | --- |
| `.env` with `GEMINI_API_KEY=...` | Gemini answers out of the box | Users can paste a key in Settings; otherwise the tutor gives offline answers |
| `app/google-services.json` | Google sign-in and cloud backup | Builds and runs; Firebase features are off |
| `debug.keystore` | Debug signing (AI Studio provides it) | Create one: `keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -validity 10000 -dname "CN=Android Debug"` |

## How accounts work

- **Guest**: the first launch opens as a guest with sample data, which can be deleted in Settings.
- **Email profile**: stored only on this device. The password is kept as a salted PBKDF2 hash and is
  required to sign in or switch back to the profile. There is no password reset; delete the profile
  and create it again.
- **Google sign-in**: signs in to Firebase. Only these accounts sync to Firestore, under `users/{uid}`.

Deploy the Firestore rules after changing them:

```sh
firebase deploy --only firestore:rules
```

They only allow a signed-in user to read and write their own `users/{uid}` tree.

## Alarms

Sessions with an alarm are scheduled with `AlarmManager.setAlarmClock`, so they ring with the app closed
and through Doze. Sessions with repeat days (`Mon,Wed,Fri`) reschedule their next occurrence each time they
ring, and all alarms are restored after a reboot or clock change. Android 13+ asks for notification
permission on first launch; without it the alarm still rings, but no notification appears.

## AI

The tutor and quiz generator call Gemini (`gemini-3.8-flash` by default, falling back through
`gemini-flash-latest` and `gemini-2.5-flash`). With no key, in Offline mode, or when Gemini fails, answers
come from `LocalAiEngine`: an Ollama server if one is reachable (default `http://10.0.2.2:11434`, the host
machine from an emulator), otherwise built-in templates and Wikipedia summaries. Offline answers that
replace a failed Gemini call say so.

Multiple-choice and true/false answers are graded on the device; short answers and code go to Gemini.
