# Phone Assistant (personal Android app)

A voice assistant that runs **offline on the phone**: an AI model (Qwen 2.5 1.5B via
llama.cpp) for questions, plus instant phone commands. Optional free online mode with Google Gemini.

## What it can do
- **Talk or type.** Tap the mic, or use the **Assistant** tile in Quick Settings (swipe down).
  Replies are read aloud when you speak.
- **Phone commands, instant and offline**
  - "Set an alarm for 6:30", "Wake me up at 7", "Set a timer for 10 minutes"
  - "Remind me in 20 minutes to call Ali", "Remind me tomorrow at 9 to pay the bill"
  - "Call Mom", "Open WhatsApp", "Turn on the flashlight"
  - "What time is it?", "What's the date?", "How much battery?"
  - "What did I miss?" / "Summarize my WhatsApp messages" (needs notification access)
- **Online extras:** "What's the weather?" (Open-Meteo), "What's the news?" (Google News), both free.
- **Questions and chat:** answered by the offline brain, or by Gemini when online mode is on
  (falls back to offline automatically).

## First run
1. Install the APK from **Releases** (latest).
2. Open the app, tap **Download** on the banner: the offline brain is about 1 GB (once, Wi-Fi recommended).
3. Optional: Settings → **Online mode** → paste a free key from https://aistudio.google.com/apikey
4. Optional: Settings → **Offline speech** → download English, so listening works without internet.
5. Optional: add the **Assistant** tile: swipe down → edit tiles → drag "Assistant" in.

Updates come from inside the app (Settings → Check for updates).

## How it's built
- `app/src/main/cpp`: JNI bridge to llama.cpp (pinned tag in `CMakeLists.txt`), built for arm64
  with ARMv8.2 dot-product kernels; the Q4_0 model uses llama.cpp's repacked ARM path.
  Conversation prefix is reused between turns so follow-ups are fast.
- `commands/CommandParser.java`: rule-based command understanding (unit-tested in CI).
- `Assistant.kt`: routes commands vs questions, streams replies, speaks sentence by sentence.
- CI builds every push; pushes to `main` publish a Release that the in-app updater installs.
  Signed with the committed `app/assistant.keystore` (personal use only). `versionCode` = 100 + run number.

## Tuning
- **Speed (CPU threads)** in Settings: 4 is best on most phones (Helio G99: 2 fast + 6 slow cores).
- To try a different model, change `ModelManager.kt` (any GGUF chat model; Q4_0 is fastest on ARM).
