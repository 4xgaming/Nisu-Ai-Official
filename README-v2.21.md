# NISU AI v2.21: strict wake word + sleep mode

## Behaviour
- Two modes, one authoritative holder (`ModeController` in `AssistantMode.kt`): **SLEEP** and **ACTIVE**.
- Wake (SLEEP -> ACTIVE): the WHOLE utterance must be exactly "হে নিশু" / "Hey Nishu" or "ওকে নিশু" / "Okay Nishu"
  (plus the explicit spellings listed in `WakeCommandParser.kt`). "নিশু", "হে", "হে নিশা", extra words: no wake.
- Sleep (ACTIVE -> SLEEP): the whole utterance must be exactly "নিশু স্লিপ" / "Nishu Sleep" or "নিশু চুপ করো" / "Nishu Chup Koro".
- While SLEEP: the Gemini session is closed, `AudioIO` (mic + speaker) is stopped, queued audio / notices / tool calls are dropped,
  and only `WakeWordListener` (Android SpeechRecognizer) uses the microphone. Nothing is sent to Gemini. Recognised text is not logged or stored.
- Boot start begins in SLEEP. A manual start from the app begins ACTIVE (as in v2.20).
- Opt-in switch: Menu > Preferences > "Wake word and sleep mode" (`Prefs.WAKE_SLEEP`, default OFF). Off = v2.20 behaviour; turning it off while asleep wakes NISU AI. Automatic WhatsApp/Messenger replies run in their own service and are not affected by sleep.

## Limitations (please read)
- Android's SpeechRecognizer is NOT a wake-word engine. It can miss the phrase, mis-hear it, or wake on a similar sound that it
  transcribes as an allowed phrase. The parser is strict, but recognition accuracy depends on the phone and language pack.
- The recogniser may use Google's speech service (network) unless an on-device Bengali pack is installed (Android 13+).
  That audio goes to the system speech service, never to Gemini.
- In ACTIVE mode the microphone streams to Gemini Live, so the sleep command is detected from Gemini's input transcript.
  The audio of the sleep command itself has therefore already been sent. After detection, replies, audio and tool calls are blocked.
  A phone action that was already running at that moment cannot be aborted.
- A wake phrase followed by a request in one breath ("Hey Nishu, call mom") does not wake: wake first, then speak.
- Some phones play a start/stop beep for each recogniser restart.
- Background limits: needs the microphone permission, the foreground service notification, and battery optimisation set to
  "Unrestricted". Some vendors (Xiaomi, Oppo, Samsung...) still kill background services. Android 14+ blocks microphone
  services started from boot, so open the app once after a restart. The wake lock is held for 6 hours per start.
- No audio focus is requested while sleeping, so music is not paused.
- Auto-reply for WhatsApp/Messenger is a separate service and keeps working while asleep.

## Files
New: `WakeCommandParser.kt`, `AssistantMode.kt`, `WakeWordListener.kt`, tests in `app/src/test/java/ai/nisu/assistant/`.
Changed: `AssistantService.kt`, `AppState.kt`, `Prefs.kt`, `Ui.kt`, `MainActivity.kt`, `AiActivity.kt`, `BootReceiver.kt`, `app/build.gradle.kts`
(version 2.21 / code 14, `testImplementation junit:4.13.2`). No API keys, signing, package name or other dependencies touched.

## Build and test
    ./gradlew :app:testDebugUnitTest     # unit tests
    ./gradlew :app:assembleDebug         # APK in app/build/outputs/apk/debug/
(JDK 17, Android SDK 35.) NOTE: this source was not compiled or tested in the environment that produced it.

## Privacy block in the auto-reply log
`privacy_blocked_other_identity` comes from the existing v2.20 reply guard (Privacy.kt): a reply is dropped if it contains the name of a contact from ANOTHER chat. v2.21 no longer blocks a single word that the current chat already uses itself, and the log now shows `PRIV_other_identity_full_len_N` / `..._first_len_N` (length only, no names) to show which rule fired. The guard is otherwise unchanged.

## Auto-reply changes (v2.21)
- If the privacy guard stops a draft because of a name or a piece of another chat (`privacy_blocked_other_identity` / `_other_content`), the
  writer now asks the AI ONE more time with a rule that forbids names, and the same guard checks the new draft. If that also fails,
  nothing is sent (as before). Log lines: `..._retry_without_names`, `PRIV_other_identity_full_len_N`.
- Pause between two confirmed sends can now be 2, 3, 4 or 5 seconds (default 3 s, was 3-5 / 4 s). One queue serves all chats, so
  several people writing at once are answered one after the other with that gap.
- Unchanged on purpose: groups are never answered (private chats only; a chat whose kind Android does not reveal is treated as not private),
  the 30 s owner wait before a reply, the per-person reply limit (Settings: Unlimited / 20 / 50), and PAUSED when you reply by hand.

## Reply limit changes (v2.21)
- **Unlimited:** every conversation closed by the reply limit is opened again at once and shows no limit (only the number of replies).
  Counters are kept, so choosing 20 / 50 again limits those people again on their next message (no second closing message is sent).
  Choosing a higher limit (20 -> 50) opens people whose counter is below the new limit. "Stop" by you and blocks are never touched.
- **Hourly refresh:** a person who reached the limit is refreshed 1 hour after their last automatic reply (counter back to 0, closing message
  allowed again, closure removed). Checked when their next message arrives, and by housekeeping every 10 minutes.
