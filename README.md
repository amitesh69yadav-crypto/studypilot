# StudyPilot

Study planner for university students, on Android and Windows: timetable with exact alarms, topic
tracking with spaced repetition, weekend revision quizzes, an AI tutor, and importing shared AI chats
as study notes. The phone and desktop apps share one data set over your Wi-Fi.

Kotlin Multiplatform, Compose (Jetpack on Android, Compose Multiplatform on desktop), Room. AI runs on the phone (LiteRT-LM) or on your own PC (Ollama); no API keys.
Firebase Auth + Firestore for optional cloud backup.

## Build

Requirements: JDK 17+, Android SDK 37.

```sh
./gradlew assembleDebug             # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest         # unit + Robolectric tests, including a smoke test that renders every screen
./gradlew :shared:desktopTest       # phone-PC sync tests against two real databases
./gradlew :desktop:run              # desktop app
./gradlew :desktop:packageMsi       # Windows installer: desktop/build/compose/binaries/main/msi/
```

Modules: `shared` holds the screens, viewmodel, database and AI (Android + desktop JVM targets);
`app` is the Android shell (alarms, Firebase, on-device models); `desktop` is the Windows window, its
alarms and the sync server. Platform differences go through `Platform` and `PlatformActions`.

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

### Taking quiz after quiz

Quizzes are multiple choice (other question types can be switched on in the quiz settings), with the
options shuffled. Every finished quiz records which questions it asked, and that record syncs. The
next quiz first uses questions never asked, then has new ones written. The model is shown what was
already asked and pointed at a different angle each time; rewordings of an earlier question count as
repeats. Only when a topic's material is exhausted do the questions asked longest ago come back.
After you submit a quiz, the next questions for those topics are written in the background, so the
following quiz starts quickly. Without a model, multiple-choice questions are built from the material
itself (your questions to the tutor, and statements from your notes), with wrong options taken from
your other topics.

### Subjects

Topics are filed under subjects (courses). **Topics** opens on **Subjects**: one card per subject with
its topic count, mastery and a **Quiz** button, plus **General** for topics without a subject. Open a
subject to see its topics, add one to it, or quiz on just that subject. A topic's subject can be changed
from its details, and deleting a subject moves its topics to General. The quiz card on **Quizzes** also
has a subject picker.

### What quizzes are made from

Quizzes draw on everything stored, not only topics typed in by hand. Each AI Tutor conversation, each
imported chat that nothing was extracted from, and each extracted concept gets a quiz topic of its own
(`QuizSources`). These topics refresh when the conversation or chat changes and disappear when it is
deleted. A topic you delete stays deleted. Under **Question Source** in the quiz settings, "Tutor Chats"
picks only conversations. With no model available, questions come from the material itself: your own
questions to the tutor, and the key points of its answers.

## Desktop app and phone sync

The desktop app is the full app with the same screens, using Ollama on the same PC for the tutor.
Its data lives in `%APPDATA%\StudyPilot`.

To connect a phone: on the PC open **Settings > Phone > Pair a phone**, then on the phone open
**Settings > PC > Scan code** (or **Type it in** with the address and 6-digit code shown on the PC).
Allow StudyPilot through Windows Firewall on private networks when asked. Pairing happens once.

After that, while the phone app is open on the same Wi-Fi, it syncs with the PC every 15 seconds and
right after each change. Everything syncs: subjects, timetable, topics, quizzes, tasks, imported
chats and tutor conversations, plus saved profiles. The newest edit to a record wins; deletes sync too.
On the first sync the PC opens the same profile as the phone. Nothing goes through a cloud service.
Traffic is plain HTTP on your local network and only paired phones (holding a token from pairing) are
answered.

How it works: SQLite triggers record each insert, update and delete in a `sync_ids` table (global id,
change time, change number, device). Each side sends the changes after the last number the other
saw, with references to other records sent as global ids (`SyncEngine`, `SyncHost`, `SyncClient`).

### Free cloud model, and how work is split

With a free Groq API key (console.groq.com, no card) in **Settings > AI > Cloud**, answers and quiz
questions go to a large cloud model (Llama 3.3 70B by default), which is fast and makes fewer
mistakes than the local models. Any OpenAI-compatible provider works ("Other provider", e.g.
OpenRouter). The key stays on the device.

| Work | Goes to |
| --- | --- |
| Tutor answers, quiz questions | Cloud, then PC, then phone model, then built-in |
| Summaries, concept extraction, grading | Cloud while under 70% of the day's free budget, otherwise PC, then phone |
| Searching your own material | The device itself (no model) |

A rate-limit reply pauses the cloud until the provider says to retry; work moves to the next model
meanwhile. Nothing is ever charged: past the free limit the provider just says no.

### Answers from your own material (RAG)

Imported chats, tutor conversations, notes and extracted concepts are cut into passages and indexed
on the device (`KnowledgeIndex`, BM25 keyword search). For each tutor question the best-matching
passages go to the model with the question; it answers from them, cites them as [1], and the cited
chats and notes are listed under the answer. Imported chats open their share link.

### Imported chat links

**Check for new messages** on an imported chat opens its share link again, adds only the new
exchanges, extracts their concepts and makes them quiz material. Opening the chat library checks
links that weren't checked in the last six hours. A share link is a snapshot: ChatGPT and most others
show messages sent after sharing only once the share link is updated on their side.

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
