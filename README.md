# Phone Assistant (personal Android app)

A voice assistant that runs **offline on the phone**: an AI model (Qwen 2.5 1.5B via
llama.cpp) for questions, plus instant phone commands. Optional free online mode with Google Gemini.

## What it can do
- **Talk, type, or say "Hey Assistant"** (offline wake word, works with the app closed). Also the
  **Assistant** tile in Quick Settings. Replies are read aloud when you speak.
- **Phone commands, instant and offline**
  - Alarms, timers, reminders: "wake me up at 6:30", "remind me in 20 minutes to call Ali"
  - "Call Mom", "Send a WhatsApp to Ali saying I'm on my way", "Text Dad saying hello"
  - "Open WhatsApp", "Open google.com", "Search for biryani in Lahore", "Play Coke Studio on YouTube"
  - "Volume up", "Set brightness to 30%", "Put phone on silent", "Turn on the flashlight"
  - "Turn on Wi-Fi / Bluetooth / mobile data" (opens the switch: Android doesn't let apps flip these)
  - Time, date, battery; "What did I miss?" summarizes notifications
- **Online extras:** weather (Open-Meteo) and news (Google News), both free.
- **Questions and chat:** offline brain, or Gemini when online mode is on.
- **Organize:** "What's on my calendar today?", "Schedule a meeting tomorrow at 3", "Add milk to my
  shopping list", "Take a note…", "Remember I parked on level 2" → "Where did I park?"
- **Exact answers (offline):** maths ("15% of 2400"), conversions ("10 marla in square feet",
  "5 km to miles"; currency online), and **prayer times** for your location (Karachi method,
  Hanafi Asr, checked against the Adhan library to within 1 minute).
- **More:** music control ("next song"), directions, "where am I", share your location on WhatsApp,
  camera and selfies, translations, contact numbers, and "good morning" for a daily briefing.
- **Chats are saved**; open old ones from the history button. Lists and memories are on the list button.

## First run
1. Install the APK from **Releases** (latest).
2. Open the app, tap **Download** on the banner: the offline brain is about 1 GB (once, Wi-Fi recommended).
3. Optional: Settings → **Online mode** → paste a free key from https://aistudio.google.com/apikey
4. Optional: Settings → **Offline speech** → download English, so listening works without internet.
5. Optional: add the **Assistant** tile: swipe down → edit tiles → drag "Assistant" in.
6. Optional: Settings → **Wake word** (downloads a 40 MB offline model) and allow
   "Display over other apps" so "Hey Assistant" can open the app. After a phone restart,
   open the app once to start listening again.

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
