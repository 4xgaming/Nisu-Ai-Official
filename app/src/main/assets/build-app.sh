#!/data/data/com.termux/files/usr/bin/bash
# NISU AI app builder - runs inside Termux.
# Usage: build-app.sh setup | build SLUG | status SLUG | errors SLUG | apk SLUG | list | dump SLUG
#        | sources | import SLUG B64NAME | context SLUG | readfull SLUG PATH
export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export HOME="${HOME:-/data/data/com.termux/files/home}"
export PATH="$PREFIX/bin:$PATH"
TOOLS="$HOME/nisu-tools"
APPS="$HOME/nisu-apps"
GRADLE_HOME="$HOME/gradle-8.9"
export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_OPTS="-Dorg.gradle.native=false -Xmx1g"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$TOOLS" "$APPS"

cmd="${1:-}"
slug="${2:-}"
arg3="${3:-}"
if [ -n "$slug" ] && ! [[ "$slug" =~ ^[a-z0-9]+$ ]]; then echo "bad project name"; exit 2; fi

wakelock()   { command -v termux-wake-lock   >/dev/null 2>&1 && termux-wake-lock   || true; }
wakeunlock() { command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock || true; }

do_setup() {
  ST="$TOOLS/setup.status"; LOG="$TOOLS/setup.log"
  echo RUNNING > "$ST"; : > "$LOG"
  wakelock
  (
    set -e
    export DEBIAN_FRONTEND=noninteractive
    pkg update -y || echo "(pkg update had a problem; continuing)"
    pkg install -y openjdk-17 aapt2 curl unzip tar
    [ -x "$PREFIX/bin/aapt2" ] || { echo "aapt2 was not installed"; exit 1; }
    [ -x "$JAVA_HOME/bin/java" ] || { echo "Java 17 was not installed"; exit 1; }
    mkdir -p "$ANDROID_HOME/licenses"
    printf '\n8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n' > "$ANDROID_HOME/licenses/android-sdk-license"
    printf '\n84831b9409646a918e30573bab4c9c91346d8abd\n' > "$ANDROID_HOME/licenses/android-sdk-preview-license"
    if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
      rm -f "$HOME/gradle-8.9-bin.zip"
      curl -L --fail --retry 3 -o "$HOME/gradle-8.9-bin.zip" https://services.gradle.org/distributions/gradle-8.9-bin.zip
      unzip -q -o "$HOME/gradle-8.9-bin.zip" -d "$HOME"
      rm -f "$HOME/gradle-8.9-bin.zip"
    fi
    chmod +x "$GRADLE_HOME/bin/gradle"
    touch "$TOOLS/.ready"
  ) >> "$LOG" 2>&1
  rc=$?
  wakeunlock
  if [ $rc -eq 0 ]; then echo OK > "$ST"; else echo FAILED > "$ST"; fi
}

do_build() {
  P="$APPS/$slug"; ST="$P/.nisu-status"; LOG="$P/build.log"
  [ -d "$P" ] || { echo "no such project"; exit 2; }
  if [ ! -f "$TOOLS/.ready" ]; then
    echo "The build tools are not installed yet." > "$P/.nisu-errors"; echo FAILED > "$ST"; exit 3
  fi
  echo RUNNING > "$ST"; : > "$LOG"; rm -f "$P/.nisu-errors"
  wakelock
  cd "$P" || exit 2
  echo "sdk.dir=$ANDROID_HOME" > local.properties
  HEAP="${NISU_HEAP:-1536m}"
  "$GRADLE_HOME/bin/gradle" assembleDebug --no-daemon --console=plain \
    -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" \
    -Pkotlin.compiler.execution.strategy=in-process \
    -Dorg.gradle.jvmargs="-Xmx$HEAP -Dfile.encoding=UTF-8" >> "$LOG" 2>&1
  rc=$?
  wakeunlock
  APK="$P/app/build/outputs/apk/debug/app-debug.apk"
  if [ ! -f "$APK" ]; then
    APK=$(find "$P" -path '*/build/outputs/apk/debug/*' -name '*.apk' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n 1 | cut -d' ' -f2-)
  fi
  if [ $rc -eq 0 ] && [ -n "$APK" ] && [ -f "$APK" ]; then
    cp "$APK" "$P/$slug.apk"
    OUT="$P/$slug.apk"
    if [ -d /sdcard/Download ] && [ -w /sdcard/Download ]; then
      cp "$APK" "/sdcard/Download/NISU-AI-$slug.apk" && OUT="/sdcard/Download/NISU-AI-$slug.apk"
    fi
    echo "$OUT" > "$P/.nisu-apk"
    echo OK > "$ST"
  else
    grep -E '^e: |error:|What went wrong|Execution failed|Could not |AAPT|Killed|OutOfMemory|No space' -A2 "$LOG" | head -90 > "$P/.nisu-errors"
    [ -s "$P/.nisu-errors" ] || tail -n 30 "$LOG" > "$P/.nisu-errors"
    echo FAILED > "$ST"
  fi
}

do_status() {
  P="$APPS/$slug"
  if [ -f "$P/.nisu-status" ]; then head -n 1 "$P/.nisu-status"; else echo NONE; fi
  [ -f "$P/build.log" ] && tail -n 1 "$P/build.log"
  return 0
}

do_list() {
  cd "$APPS" 2>/dev/null || exit 0
  for d in */; do
    d="${d%/}"
    [ -d "$d" ] || continue
    echo "$d $(head -n 1 "$d/.nisu-status" 2>/dev/null || echo NEW)"
  done
}

do_dump() {
  cd "$APPS/$slug" 2>/dev/null || exit 2
  find app/src app/build.gradle.kts -type f \( -name '*.kt' -o -name '*.java' -o -name '*.xml' -o -name '*.html' -o -name '*.css' -o -name '*.js' -o -name '*.json' -o -name '*.kts' \) 2>/dev/null | sort | head -40 | while read -r f; do
    echo "=====FILE $f"
    head -c 24000 "$f"
    echo
  done | head -c 90000
}

# ---- existing source projects (Download folder) ----
dl_dir() {
  for d in "$HOME/storage/downloads" /sdcard/Download /storage/emulated/0/Download; do
    if [ -d "$d" ] && ls "$d" >/dev/null 2>&1; then echo "$d"; return 0; fi
  done
  return 1
}

do_sources() {
  D=$(dl_dir) || { echo "NO_DOWNLOAD_ACCESS"; exit 4; }
  cd "$D" || { echo "NO_DOWNLOAD_ACCESS"; exit 4; }
  ls -1t 2>/dev/null | head -n 60 | while IFS= read -r n; do
    [ -n "$n" ] || continue
    if [ -d "$n" ]; then
      if find "$n" -maxdepth 3 \( -name settings.gradle -o -name settings.gradle.kts \) 2>/dev/null | grep -q .; then
        echo "$(stat -c %Y "$n")|folder|$n"
      fi
    else
      case "$n" in
        *.zip|*.ZIP)
          if unzip -Z1 "$n" 2>/dev/null | grep -qE '(^|/)settings\.gradle(\.kts)?$'; then
            echo "$(stat -c %Y "$n")|zip|$n"
          fi ;;
      esac
    fi
  done | head -n 15
}

do_import() {
  [ -n "$slug" ] || { echo "no project name"; exit 2; }
  D=$(dl_dir) || { echo "NO_DOWNLOAD_ACCESS"; exit 4; }
  name=$(printf '%s' "$arg3" | base64 -d 2>/dev/null)
  case "$name" in ""|*/*|*..*|-*) echo "bad source name"; exit 2 ;; esac
  [ -e "$D/$name" ] || { echo "source not found in Download"; exit 2; }
  P="$APPS/$slug"; TMP="$TOOLS/import-$$"
  rm -rf "$TMP" "$P"; mkdir -p "$TMP"
  if [ -d "$D/$name" ]; then
    cp -r "$D/$name/." "$TMP/" || { echo "copy failed"; rm -rf "$TMP"; exit 2; }
  else
    unzip -q -o "$D/$name" -d "$TMP" || { echo "unzip failed"; rm -rf "$TMP"; exit 2; }
  fi
  S=$(find "$TMP" -maxdepth 4 \( -name settings.gradle -o -name settings.gradle.kts \) -printf '%d %h\n' 2>/dev/null | sort -n | head -n 1 | cut -d' ' -f2-)
  [ -n "$S" ] || { echo "no Gradle project (settings.gradle) found"; rm -rf "$TMP"; exit 2; }
  mv "$S" "$P" || { echo "move failed"; rm -rf "$TMP"; exit 2; }
  rm -rf "$TMP"
  rm -rf "$P/.gradle" "$P/build"
  for m in "$P"/*/; do
    if [ -f "$m/build.gradle" ] || [ -f "$m/build.gradle.kts" ]; then rm -rf "$m/build"; fi
  done
  [ -f "$P/gradle.properties" ] && sed -i '/^org\.gradle\.java\.home/d' "$P/gradle.properties"
  rm -f "$P/local.properties" "$P/.nisu-status" "$P/.nisu-errors" "$P/.nisu-apk"
  printf '%s' "$name" > "$P/.nisu-src"
  echo IMPORTED
}

# Files the repair model needs: the Gradle files plus every file named in the last build errors (inside the project only).
do_context() {
  P="$APPS/$slug"; cd "$P" 2>/dev/null || exit 2
  {
    for f in settings.gradle settings.gradle.kts build.gradle build.gradle.kts gradle.properties gradle/libs.versions.toml \
             */build.gradle */build.gradle.kts app/src/main/AndroidManifest.xml; do
      [ -f "$f" ] && echo "$f"
    done
    if [ -s .nisu-errors ]; then
      sed "s#file://##g; s#$P/##g" .nisu-errors | grep -oE '[A-Za-z0-9_./-]+\.(kt|java|xml|kts|gradle|toml|properties)' | sort -u | while IFS= read -r f; do
        case "$f" in /*|*..*) continue ;; esac
        [ -f "$f" ] && echo "$f"
      done
    fi
  } | awk '!seen[$0]++' | head -n 16 | while IFS= read -r f; do
    echo "=====FILE $f"
    head -c 20000 "$f"
    echo
  done | head -c 120000
}

do_readfull() {
  P="$APPS/$slug"; cd "$P" 2>/dev/null || exit 2
  f="$arg3"
  case "$f" in ""|/*|*..*) echo "bad path"; exit 2 ;; esac
  [ -f "$f" ] || { echo "no such file"; exit 2; }
  sz=$(wc -c < "$f")
  [ "$sz" -le 150000 ] || { echo "TOOBIG"; exit 3; }
  cat "$f"
}

case "$cmd" in
  setup)  do_setup ;;
  build)  do_build ;;
  status) do_status ;;
  errors) head -c 8000 "$APPS/$slug/.nisu-errors" 2>/dev/null ;;
  apk)    cat "$APPS/$slug/.nisu-apk" 2>/dev/null ;;
  list)   do_list ;;
  dump)   do_dump ;;
  sources) do_sources ;;
  import) do_import ;;
  context) do_context ;;
  readfull) do_readfull ;;
  *) echo "usage: build-app.sh setup | build SLUG | status SLUG | errors SLUG | apk SLUG | list | dump SLUG | sources | import SLUG B64NAME | context SLUG | readfull SLUG PATH"; exit 2 ;;
esac
