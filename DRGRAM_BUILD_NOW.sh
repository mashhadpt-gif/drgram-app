#!/usr/bin/env bash
# ============================================================
#  DrGram — One-Command APK Builder
#  Builds the signed release APK (black-gold premium fork).
#
#  Usage:
#      ./DRGRAM_BUILD_NOW.sh            # signed release APK (installable)
#      ./DRGRAM_BUILD_NOW.sh debug      # debug APK (auto-signed with release key)
#
#  Requirements auto-handled where possible:
#    - JDK 17 or 21
#    - Android SDK (platform 36, build-tools 36.0.0, NDK 27.2.12479018, CMake 3.22.1)
#    - Native submodules (ffmpeg, td, boringssl, ...) — auto-fetched
#
#  Disk: ~15 GB free recommended   RAM: 8 GB+ recommended
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

BUILD_TYPE="release"
if [ "${1:-}" = "debug" ]; then BUILD_TYPE="debug"; fi

GOLD='\033[1;33m'; RED='\033[1;31m'; GRN='\033[1;32m'; DIM='\033[2m'; RST='\033[0m'
step() { echo -e "${GOLD}▶ $*${RST}"; }
ok()   { echo -e "${GRN}✔ $*${RST}"; }
die()  { echo -e "${RED}✖ $*${RST}"; exit 1; }

echo -e "${GOLD}"
cat <<'BANNER'
  ██████╗ ██████╗  ██████╗ ██████╗  █████╗ ███╗   ███╗
  ██╔══██╗██╔══██╗██╔════╝ ██╔══██╗██╔══██╗████╗ ████║
  ██║  ██║██████╔╝██║  ███╗██████╔╝███████║██╔████╔██║
  ██║  ██║██╔══██╗██║   ██║██╔══██╗██╔══██║██║╚██╔╝██║
  ██████╔╝██║  ██║╚██████╔╝██║  ██║██║  ██║██║ ╚═╝ ██║
  ╚═════╝ ╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝     ╚═╝
         Final APK Build — black-gold premium edition
BANNER
echo -e "${RST}"

# ---------- 1. Java ----------
step "Checking Java..."
if ! command -v java >/dev/null 2>&1; then
  die "Java not found. Install JDK 17:  sudo apt install openjdk-17-jdk   (Ubuntu/Debian)"
fi
JAVA_VER="$(java -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1)"
if [ "$JAVA_VER" -lt 17 ]; then
  die "JDK 17+ required (found $JAVA_VER). Install:  sudo apt install openjdk-17-jdk"
fi
ok "Java $JAVA_VER"

# ---------- 2. Android SDK ----------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -d "$HOME/Android/Sdk" ]; then SDK="$HOME/Android/Sdk"; fi
if [ -z "$SDK" ]; then SDK="$HOME/android-sdk"; fi
export ANDROID_HOME="$SDK"
mkdir -p "$SDK"

SDKM=""
for c in "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SDK/cmdline-tools/bin/sdkmanager" "$(command -v sdkmanager || true)"; do
  if [ -n "$c" ] && [ -x "$c" ]; then SDKM="$c"; break; fi
done

if [ -z "$SDKM" ]; then
  step "Android cmdline-tools not found — downloading..."
  URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  case "$(uname -s)" in
    Darwin) URL="https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip" ;;
  esac
  TMPZIP="$(mktemp -u).zip"
  curl -fL --retry 3 -o "$TMPZIP" "$URL" || die "Failed to download Android cmdline-tools (check network)"
  unzip -q "$TMPZIP" -d "$SDK/cmdline-tools-tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$SDK/cmdline-tools-tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$SDK/cmdline-tools-tmp" "$TMPZIP"
  SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"
fi
ok "SDK: $SDK"

step "Installing SDK components (platform 36, build-tools 36, NDK 27.2, CMake 3.22.1)..."
yes | "$SDKM" --licenses >/dev/null 2>&1 || true
"$SDKM" --install "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;27.2.12479018" "cmake;3.22.1" \
  | tail -5 || die "SDK component install failed"

# ---------- 3. Native submodules ----------
step "Fetching native submodules (ffmpeg / td / boringssl ...) — several GB, one-time..."
git submodule sync --recursive || true
if ! git submodule update --init --recursive --progress; then
  step "Shallow fetch failed — retrying full fetch..."
  git submodule update --init --recursive
fi
ok "Submodules ready ($(git submodule status | wc -l | tr -d ' ') modules)"

# ---------- 4. Gradle memory (RAM-adaptive) ----------
TOTAL_RAM_GB="$( (free -g 2>/dev/null | awk '/^Mem/{print $2}') || (sysctl -n hw.memsize 2>/dev/null | awk '{print int($1/1073741824)}') || echo 8 )"
if   [ "$TOTAL_RAM_GB" -le 6 ]; then XMX="2g"
elif [ "$TOTAL_RAM_GB" -le 12 ]; then XMX="4g"
elif [ "$TOTAL_RAM_GB" -le 20 ]; then XMX="6g"
else XMX="8g"; fi
step "RAM ${TOTAL_RAM_GB}GB → Gradle JVM -Xmx${XMX}"

# ---------- 5. Build ----------
TASK=":TMessagesProj_App:assembleAfat$(echo "$BUILD_TYPE" | sed 's/^\(.\)/\U\1/')"
step "Building DrGram (${BUILD_TYPE}) — first run downloads Gradle + deps, then compiles native C/C++ ..."
step "Expect 30-90+ minutes depending on CPU. Go make some tea ☕"
./gradlew "$TASK" -Dorg.gradle.jvmargs="-Xmx${XMX} -XX:MaxMetaspaceSize=1g" --stacktrace

# ---------- 6. Collect APK ----------
SRC="TMessagesProj_App/build/outputs/apk/afat/${BUILD_TYPE}/app.apk"
[ -f "$SRC" ] || SRC="$(ls -1 TMessagesProj_App/build/outputs/apk/*/*/*.apk 2>/dev/null | head -1)"
[ -n "${SRC:-}" ] && [ -f "$SRC" ] || die "APK output not found!"

VERSION="$(grep '^APP_VERSION_NAME=' gradle.properties | cut -d= -f2 | tr -d ' \r')"
OUT="DrGram-v${VERSION}-afat-${BUILD_TYPE}.apk"
cp "$SRC" "$ROOT/$OUT"

echo
ok  "════════════════════════════════════════════════════"
ok  " DrGram APK ready →  $ROOT/$OUT"
ok  " Size: $(du -h "$OUT" | cut -f1)   SHA-256: $(sha256sum "$OUT" 2>/dev/null | cut -d' ' -f1 || shasum -a 256 "$OUT" | cut -d' ' -f1)"
ok  " Signed with: DrGram Google Play key (alias: drgram)"
ok  " Install: copy to phone → allow unknown sources → install"
ok  "════════════════════════════════════════════════════"
