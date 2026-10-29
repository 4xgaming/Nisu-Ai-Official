# v2.12 manual test plan (needs a real device; nothing here was run in the authoring sandbox)

Watch the "Automation log" on the Automation screen for the step codes.

## Delay
| Setting | Do | Expect |
|---|---|---|
| Instant | send a WhatsApp/Messenger message from another phone | task due at once; reply starts as soon as the AI draft is ready (no 30 s wait) |
| Fast | same | about 15 s after the message, plus draft time |
| Normal | same | about 30 s after the message, plus draft time |
| any | change mode, kill app, reopen | selected mode still shown; next message uses it |
| any | change mode while a wait is running | that wait keeps its old deadline; the next message uses the new one |

## Limit
| Setting | Do | Expect |
|---|---|---|
| Unlimited | 25+ messages from one person | no `limit_reached`, no closing message; counter shows only a number |
| 20 | 21st message | `limit_reached` / one closing message, then silence |
| 50 | 51st message | same at 50 |
| any | restart app/phone | counter and selected limit unchanged |

## Lock
1. Lock on (PIN). Unlock, lock now, unlock: 20 times in a row. No lock screen may re-appear after a valid unlock.
2. Unlock, press power (screen off), press power: must show the lock screen. Repeat 10 times, also with fingerprint.
3. Start fingerprint prompt, press power while it is open: after screen on the phone must still be LOCKED.
4. Unlock, swipe NISU AI away from recents / `adb shell am kill ai.nisu.assistant`, turn the screen off, on: must be LOCKED.
5. Reboot: must be LOCKED (also on a phone where `adb shell settings get global boot_count` returns null).
6. Lock off in settings: never shows a lock screen, also after restart.
7. NISU automation (notification reply, phone control) still works while the lock is closed and the screen is on.

## Regression
Auto-reply on/off, pause, per-app switches, owner-replied pause, block/unblock, 3-5 s send delay chips, voice session, background service.
