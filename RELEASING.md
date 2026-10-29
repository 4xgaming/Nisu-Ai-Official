# Publishing an update (from Termux, on the phone)

Users' apps look at https://github.com/4xgaming/Nisu-Ai-Official/releases/latest every time the app opens.
If the release tag is higher than the installed versionName, they see the "New update" popup and the red "Updates" row in the side menu.

## One-time
1. `pkg install gh` then `gh auth login` (GitHub.com, HTTPS, paste a token or log in with the browser).
2. Build once with `bash build-termux.sh`. It saves the signing key to `/sdcard/Download/nisu-debug.keystore.BACKUP`.
   KEEP THAT FILE (copy it to Google Drive too). If it is lost, every user must uninstall and reinstall the app (their data is lost) before they can update.
   Never upload it to GitHub (.gitignore already blocks *.keystore*).
3. Install 3.141 by hand on the phones that should get updates. Older versions (without the updater) never see updates.

## Every update
1. In `app/build.gradle.kts` raise `versionCode` by 1 (now 152) and set `versionName` (now "3.141") to the new number, e.g. "3.142".
2. `bash build-termux.sh` -> the APK is `/sdcard/Download/NISU-AI.apk`.
3. Publish (the tag is `v` + versionName; the APK name does not matter but it must end in .apk):
```
gh release create v3.142 /sdcard/Download/NISU-AI.apk --repo 4xgaming/Nisu-Ai-Official \
   --title "NISU AI 3.142" --notes "What is new: ..."
```
The notes text is shown to the user in the update window.

Do not mark the release "draft" or "pre-release": the app only sees normal published releases.
Test it: install 3.141, publish a 3.142 release, open the app: the popup must appear.
