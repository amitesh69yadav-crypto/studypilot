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
| `debug.keystore` | Debug signing (kept out of the repo) | Create one: `keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -validity 10000 -dname "CN=Android Debug"` |

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

### Desktop look

The app uses a "glass" style (`AppStyle.Glass`) on both the PC and the phone: frosted, see-through
panels with a light rim over a painted backdrop, a serif for page titles, small spaced capitals for
labels, pill-shaped buttons, chips and tab tracks, and a near-black pill for the main action. The
window has a sidebar (pages, tools and a streak card) and a page header with the title, a one-line
purpose, the date and round glass buttons; the Today page is laid out in two columns. Text is set in Manrope
and page titles in Fraunces, both bundled with the desktop app as variable fonts
(`desktop/src/commonMain/composeResources/font`, SIL Open Font License, texts alongside in
`files/licenses`). Backdrops are
picked in **Settings > Appearance**: Olive Mist, Soft Neutral, Minimal Solid (light) and Dark Forest,
Midnight (dark).

How it's built (`shared/.../ui/theme/Glass.kt`, `GlassComponents.kt`, `ui/GlassShell.kt`): the
screens use drop-in `Card`, `Button`, `OutlinedButton`, `FilterChip` and `TabRow` that are the
Material components on the phone and glass on the desktop, and the sage palette the timetable and
chat screens use is read from the theme, so those screens follow light and dark on the desktop too.

### On the phone

The phone has the same design, laid out for a phone: the page title in the serif with round glass
buttons, a floating glass navigation bar, and the same fonts (bundled in `app/src/main/res/font`) and
backdrops (Settings > Appearance). Today is one column with the orbit under what's on now. The
Timetable opens on the Day view; the Week grid scrolls sideways. On touch, sessions on the ring and in
the week grid are picked up with a long press, so a swipe still scrolls the page. Things only the PC
needs check `LocalIsDesktop`, not the style.

### Orbit and week views

On the desktop, the day is also drawn as an **orbit**: a 24-hour ring (midnight at the top) with each
session as a pastel arc and its icon. Routines have fixed colours (meals yellow, sleep blue, exercise green,
personal teal), subjects get their own, and alarms are frosted markers. Sleep from 23:00 to 07:00 wraps
across the top. The middle shows the time now and what's on, or, while hovering or dragging, the session and its times.
Drag an arc to move it, drag either end to make it longer or shorter, click it to edit it, or click an
empty part of the ring to add a session there. **Planned / Actual** switches between the whole plan and only
what's marked done; **Unified / Layers** puts what was done on an inner ring under the plan. A legend
beside the ring shows how much of the day each subject or routine takes ("2h 15m · 9.4%"), and the
expand button opens the ring large. The Today page opens with today's orbit.

The Timetable page on the desktop has three views:
- **Week**: day columns of hours with the week's sessions as blocks. Drag a block to another time
  or day, or drag its bottom edge to change when it ends. Click an empty slot to add a session there.
  A repeating session moves in time only, since it belongs to several days.
- **Day**: the week as seven small orbits with the time planned each day, then the selected
  day's orbit and agenda, where sessions can be ticked off.
- **Planner**: the original timetable with routines, copying days and the other tools.

The code is in `shared/.../ui/orbit/`: `OrbitModel.kt` holds the plain logic (which sessions fall on a
day, snapping, dragging, lanes for overlapping blocks, legend totals), and is covered by
`OrbitModelTest`. The phone keeps its own timetable.


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

**Settings > AI > API keys** takes your own key for a cloud chatbot: Groq (free, no card; the default,
Llama 3.3 70B), OpenRouter (free ":free" models), Google Gemini, OpenAI, Anthropic Claude, Mistral,
DeepSeek, or any other OpenAI-compatible API. Each provider keeps its own saved key; pick which one
answers. Answers and quiz questions then come from that model, which is fast and makes fewer
mistakes than the local models.

No key is built into the app. Keys are stored only on the device that entered them (a separate
`studypilot_keys` store that Android doesn't back up or copy to a new phone, and that phone-PC sync
doesn't send), and each key goes only to its own provider. Anyone you share the app with adds their
own key.

| Work | Goes to |
| --- | --- |
| Tutor answers, quiz questions | Your cloud chatbot, then PC, then phone model, then built-in |
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
