# StudyPilot

Android study planner for university students: timetable with exact alarms, topic tracking with
spaced repetition, weekend revision quizzes, an AI tutor, and importing shared AI chats as study notes.

Kotlin, Jetpack Compose, Room. AI runs on the phone (LiteRT-LM) or on your own PC (Ollama); no API keys.
Firebase Auth + Firestore for optional cloud backup.

## Build

Requirements: JDK 17+, Android SDK 36.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit + Robolectric tests, including a smoke test that renders every screen
```

Files that are not in the repo:

| File | Needed for | Without it |
| --- | --- | --- |
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

Settings > AI chooses where answers come from. **Auto** tries them in this order:

1. **Your PC** with [Ollama](https://ollama.com). Strongest; needs the phone on the same Wi-Fi.
2. **A model on the phone**, downloaded once in the app. Works offline:
   - Gemma 4 E2B (2.6 GB, phones with about 6 GB+ RAM) — recommended
   - Qwen 3.5 0.8B (0.96 GB, older phones)
3. **Built-in answers** (`LocalAiEngine`): canned explanations, a small maths and code solver, and Wikipedia.
   Used when neither model is available; such answers are labelled.

Replies stream in as they're generated. Multiple-choice and true/false answers are graded without a model.

### Setting up the PC

```powershell
winget install Ollama.Ollama
ollama pull qwen3:8b
setx OLLAMA_HOST 0.0.0.0
```

Quit Ollama from the system tray and start it again so it listens on the network, and allow it through
Windows Firewall on private networks when asked. In the app, tap **Find** under "On your PC", or type the
PC's Wi-Fi address (`ipconfig` → IPv4 Address). From the emulator, the PC is `10.0.2.2`.

`qwen3:8b` (about 5 GB) fits a 6 GB+ GPU; use `qwen3:4b` on smaller machines.
