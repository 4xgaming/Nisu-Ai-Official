#!/data/data/com.termux/files/usr/bin/bash
# NISU AI - build the debug APK inside Termux (ARM phones).
# Usage:  bash build-termux.sh
# Needs ~4 GB free storage and a network connection. First run takes 10-25 minutes.
# Optional:  NISU_HEAP=1536m bash build-termux.sh   (less memory)
#            NISU_FIX_PHANTOM=1 bash build-termux.sh (rooted phones: stop Android 12+ from killing long builds)
set -e

SRC="$(cd "$(dirname "$0")" && pwd)"
HEAP="${NISU_HEAP:-2g}"
cleanup() { command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock || true; }
trap cleanup EXIT
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true   # keep the CPU awake during the build

echo "== 1/6 Installing Termux packages =="
pkg update -y || echo "(pkg update had a problem - if installs fail, run: termux-change-repo)"
pkg install -y openjdk-17 aapt2 curl unzip tar

AAPT2="$PREFIX/bin/aapt2"
if [ ! -x "$AAPT2" ]; then echo "ERROR: aapt2 was not installed ($AAPT2). Run: pkg install aapt2"; exit 1; fi

export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
if [ ! -x "$JAVA_HOME/bin/java" ]; then echo "ERROR: Java 17 not found at $JAVA_HOME. Run: pkg install openjdk-17"; exit 1; fi
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_OPTS="-Dorg.gradle.native=false -Xmx1g"

echo "== 2/6 Choosing a build folder =="
# Shared storage (/sdcard, /storage) cannot hold executable files or symlinks, so Gradle fails there with
# 'Permission denied'. Build in Termux's private home instead.
case "$SRC" in
  /sdcard/*|/storage/*|/mnt/*|/data/media/*|/sdcard|/storage)
    WORK="$HOME/nisu-build"
    echo "Project is on shared storage -> building in $WORK"
    rm -rf "$WORK"; mkdir -p "$WORK"
    (cd "$SRC" && tar cf - --exclude=./build --exclude=./app/build --exclude=./.gradle .) | (cd "$WORK" && tar xf -)
    ;;
  *) WORK="$SRC" ;;
esac
cd "$WORK"

echo "== 3/6 Preparing Android SDK folder and licenses =="
mkdir -p "$ANDROID_HOME/licenses"
printf '\n8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n' > "$ANDROID_HOME/licenses/android-sdk-license"
printf '\n84831b9409646a918e30573bab4c9c91346d8abd\n' > "$ANDROID_HOME/licenses/android-sdk-preview-license"
echo "sdk.dir=$ANDROID_HOME" > local.properties

echo "== 4/6 Getting Gradle 8.9 (the version this project is built for) =="
GRADLE_HOME="$HOME/gradle-8.9"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  rm -f "$HOME/gradle-8.9-bin.zip"
  curl -L --fail --retry 3 -o "$HOME/gradle-8.9-bin.zip" https://services.gradle.org/distributions/gradle-8.9-bin.zip
  unzip -q -o "$HOME/gradle-8.9-bin.zip" -d "$HOME"
  rm -f "$HOME/gradle-8.9-bin.zip"
fi
chmod +x "$GRADLE_HOME/bin/gradle"

if [ "${NISU_FIX_PHANTOM:-0}" = "1" ] && command -v su >/dev/null 2>&1; then
  echo "Asking root to stop Android from killing background build processes..."
  su -c "settings put global settings_enable_monitor_phantom_procs false; device_config put activity_manager max_phantom_processes 2147483647" || true
fi

# v3.141: UPDATES NEED THE SAME SIGNING KEY EVERY TIME. Gradle signs the debug APK with ~/.android/debug.keystore. If that file is lost (Termux
# reinstalled, new phone) the next APK has another signature and Android refuses to update the installed app. So the key is kept in a backup
# next to the APK, and put back before the build when it is missing.
KS="$HOME/.android/debug.keystore"; KSB="/sdcard/Download/nisu-debug.keystore.BACKUP"
if [ ! -f "$KS" ] && [ -f "$KSB" ]; then mkdir -p "$HOME/.android" && cp "$KSB" "$KS" && echo "Signing key restored from $KSB"; fi

echo "== 5/6 Building (Termux's own aapt2 replaces the x86 one that cannot run on ARM) =="
set +e
"$GRADLE_HOME/bin/gradle" assembleDebug --no-daemon --stacktrace --console=plain \
  -Pandroid.aapt2FromMavenOverride="$AAPT2" \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs="-Xmx$HEAP -Dfile.encoding=UTF-8" 2>&1 | tee "$WORK/build.log"
RC=${PIPESTATUS[0]}
set -e

if [ "$RC" -ne 0 ]; then
  echo
  echo "=================== BUILD FAILED (exit $RC) ==================="
  grep -n "^e: \|error:\|What went wrong" -A3 "$WORK/build.log" | head -50 || true
  if grep -q "Killed\|OutOfMemory\|exit code 137\|Java heap" "$WORK/build.log"; then
    echo "Looks like the phone ran out of memory or Android killed the build."
    echo "Close other apps and retry with:  NISU_HEAP=1536m bash build-termux.sh"
    echo "On rooted phones you can also try: NISU_FIX_PHANTOM=1 bash build-termux.sh"
  fi
  if grep -q "Permission denied" "$WORK/build.log"; then
    echo "'Permission denied': make sure the project is NOT inside /sdcard (this script copies it to \$HOME automatically)."
  fi
  if [ -d /sdcard/Download ] && [ -w /sdcard/Download ]; then cp "$WORK/build.log" /sdcard/Download/nisu-build.log && echo "Full log saved: /sdcard/Download/nisu-build.log"
  else echo "Full log: $WORK/build.log"; fi
  exit "$RC"
fi

if [ -f "$KS" ] && [ ! -f "$KSB" ] && [ -d /sdcard/Download ] && [ -w /sdcard/Download ]; then cp "$KS" "$KSB" && echo "Signing key saved to $KSB: keep this file safe (also copy it to Google Drive). Never upload it to GitHub."; fi

echo "== 6/6 Copying the APK =="
APK="$WORK/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then echo "Build finished but the APK was not found."; exit 1; fi
if [ -d /sdcard/Download ] && [ -w /sdcard/Download ]; then
  cp "$APK" /sdcard/Download/NISU-AI.apk && echo "Done! APK: /sdcard/Download/NISU-AI.apk"
else
  cp "$APK" "$HOME/NISU-AI.apk" && echo "Done! APK: $HOME/NISU-AI.apk  (run termux-setup-storage to copy it to Downloads)"
fi
