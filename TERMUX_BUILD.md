# Build NISU AI in Termux

1. Install Termux from F-Droid (not the old Play Store version).
2. Run `termux-setup-storage` once and allow access.
3. Unzip the project, then:
   ```
   cd NISU AI
   bash build-termux.sh
   ```
4. Install `/sdcard/Download/NISU-AI.apk`.

If it stops with "Out of memory", close other apps and run it again.
The GitHub Actions workflow is unchanged and still works.
