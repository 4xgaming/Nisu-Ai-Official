# Free tier: keep the free minutes in the cloud (no server needed)

Without this, the free time is kept on the phone and checked against the real internet clock
(phone clock tricks do not work), but "Clear data" / reinstall gives a new 15 minutes.
With this (free Firebase database, set up from a phone browser), clearing data does NOT help: the minutes are saved under the device id.

1. Open https://console.firebase.google.com (sign in with Google) -> Add project -> any name -> continue (turn Analytics off).
2. Left menu: Build -> Realtime Database -> Create Database -> pick a location -> start in "Test mode" -> Enable.
3. At the top of the Data tab copy the address, like `https://nisu-ai-default-rtdb.firebaseio.com` (no slash at the end).
4. Open the Rules tab, replace everything with this, press Publish (test mode rules stop working after 30 days, these do not):
```
{
  "rules": {
    "free": {
      "$id": { ".read": true, ".write": true, ".validate": "newData.hasChildren(['used_ms','out_at'])" }
    }
  }
}
```
5. In `app/src/main/java/ai/nisu/assistant/FreeTier.kt` put the address into `const val DB_URL = "https://....firebaseio.com"`, build, install.

The app saves `free/<device id>` = used_ms, out_at (internet time). 15 minutes used = locked 6 hours, then 15 minutes again.
