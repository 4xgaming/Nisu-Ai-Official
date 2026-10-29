#!/data/data/com.termux/files/usr/bin/bash
# Build the debug APK of this project inside Termux (ARM phones).
# Usage:  bash build-termux.sh
# Needs about 4 GB free storage and a network connection. The first run takes 10-25 minutes.
# Optional:  NISU_HEAP=1536m bash build-termux.sh   (less memory)
set -e

SRC="$(cd "$(dirname "$0")" && pwd)"
HEAP="${NISU_HEAP:-2g}"
cleanup() { command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock || true; }
trap cleanup EXIT
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true

echo "== 1/5 Installing Termux packages =="
pkg update -y || echo "(pkg update had a problem - if installs fail, run: termux-change-repo)"
pkg install -y openjdk-17 aapt2 curl unzip tar

AAPT2="$PREFIX/bin/aapt2"
if [ ! -x "$AAPT2" ]; then echo "ERROR: aapt2 was not installed. Run: pkg install aapt2"; exit 1; fi
export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
if [ ! -x "$JAVA_HOME/bin/java" ]; then echo "ERROR: Java 17 not found. Run: pkg install openjdk-17"; exit 1; fi
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_OPTS="-Dorg.gradle.native=false -Xmx1g"

echo "== 2/5 Choosing a build folder =="
# Shared storage (/sdcard) cannot hold executable files or symlinks; build in Termux's private home instead.
case "$SRC" in
  /sdcard/*|/storage/*|/mnt/*|/data/media/*|/sdcard|/storage)
    WORK="$HOME/export-build"
    echo "Project is on shared storage -> building in $WORK"
    rm -rf "$WORK"; mkdir -p "$WORK"
    (cd "$SRC" && tar cf - --exclude=./build --exclude=./app/build --exclude=./.gradle .) | (cd "$WORK" && tar xf -)
    ;;
  *) WORK="$SRC" ;;
esac
cd "$WORK"

echo "== 3/5 Preparing Android SDK folder and licenses =="
mkdir -p "$ANDROID_HOME/licenses"
printf '\n8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n' > "$ANDROID_HOME/licenses/android-sdk-license"
printf '\n84831b9409646a918e30573bab4c9c91346d8abd\n' > "$ANDROID_HOME/licenses/android-sdk-preview-license"
echo "sdk.dir=$ANDROID_HOME" > local.properties

echo "== 4/5 Getting Gradle 8.9 =="
GRADLE_HOME="$HOME/gradle-8.9"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  rm -f "$HOME/gradle-8.9-bin.zip"
  curl -L --fail --retry 3 -o "$HOME/gradle-8.9-bin.zip" https://services.gradle.org/distributions/gradle-8.9-bin.zip
  unzip -q -o "$HOME/gradle-8.9-bin.zip" -d "$HOME"
  rm -f "$HOME/gradle-8.9-bin.zip"
fi
chmod +x "$GRADLE_HOME/bin/gradle"

echo "== 5/5 Building (Termux's own aapt2 replaces the x86 one that cannot run on ARM) =="
set +e
"$GRADLE_HOME/bin/gradle" assembleDebug --no-daemon --stacktrace --console=plain \
  -Pandroid.aapt2FromMavenOverride="$AAPT2" \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs="-Xmx$HEAP -Dfile.encoding=UTF-8" 2>&1 | tee "$WORK/build.log"
RC=${PIPESTATUS[0]}
set -e
if [ "$RC" -ne 0 ]; then
  echo; echo "=================== BUILD FAILED (exit $RC) ==================="
  grep -n "^e: \|error:\|What went wrong" -A3 "$WORK/build.log" | head -50 || true
  if grep -q "Killed\|OutOfMemory\|exit code 137\|Java heap" "$WORK/build.log"; then
    echo "Out of memory. Close other apps and retry with:  NISU_HEAP=1536m bash build-termux.sh"
  fi
  echo "Full log: $WORK/build.log"
  exit "$RC"
fi

APK="$(find "$WORK/app/build/outputs/apk/debug" -name '*.apk' 2>/dev/null | head -n 1)"
if [ -z "$APK" ]; then echo "Build finished but no APK was found in app/build/outputs/apk/debug."; exit 1; fi
NAME="$(basename "$SRC").apk"
if [ -d /sdcard/Download ] && [ -w /sdcard/Download ]; then
  cp "$APK" "/sdcard/Download/$NAME" && echo "Done! APK: /sdcard/Download/$NAME"
else
  cp "$APK" "$HOME/$NAME" && echo "Done! APK: $HOME/$NAME  (run termux-setup-storage to copy it to Downloads)"
fi
