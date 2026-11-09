#!/bin/sh
set -eu
unset POWER_LOG_STRESS_ARGUMENT
cd "$(dirname "$0")/.."
root=$(pwd)
work=""
tier=""
for argument in "$@"; do
  case "$argument" in
    --full) tier=--full ;;
    -*) echo "Usage: sh scripts/verify.sh [directory] [--full]" >&2; exit 2 ;;
    *) work=$argument ;;
  esac
done
unset POWER_LOG_BROWSER_STRESS
export POWER_LOG_BROWSER_STRESS_SIZE=smoke
export CI=1
node_bin="/opt/homebrew/opt/node@$(cat .nvmrc)/bin"
if [ -x "$node_bin/node" ]; then PATH="$node_bin:$PATH"; fi
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_SERIAL=emulator-5554
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export FIT_PYTHON="${FIT_PYTHON:-$HOME/.cache/power-log/fit-venv/bin/python}"
if [ -z "${BUNDLE_PATH:-}" ] && [ -d "$HOME/.cache/power-log/bundle" ]; then export BUNDLE_PATH="$HOME/.cache/power-log/bundle"; fi
if [ -n "$tier" ]; then
  echo "Verification toolchain:"
  node --version
  npm --version
  git --version
  java -version
  xcodebuild -version
  xcrun --sdk iphoneos --show-sdk-version
  xcrun --sdk watchos --show-sdk-version
  xcrun swift --version
  xcrun swift-format --version
  ruby --version
  bundle --version
  bundle exec pod --version
  "$FIT_PYTHON" --version
  python3 --version
  "$FIT_PYTHON" -c 'from importlib.metadata import version; print("garmin-fit-sdk " + version("garmin-fit-sdk"))'
  curl --version
  echo "ktfmt $(sed -n 's/^ktfmt_version=//p' scripts/format.sh)"
  node <<'JS'
const { packages } = require('./package-lock.json');
for (const name of ['expo', 'typescript', 'eslint', 'prettier', 'vitest', '@playwright/test', 'patch-package']) {
  console.log(`${name} ${packages[`node_modules/${name}`].version} (lockfile)`);
}
JS
  if [ -f node_modules/expo/template.tgz ]; then
    tar -xOzf node_modules/expo/template.tgz package/android/gradle/wrapper/gradle-wrapper.properties | sed -n 's/^distributionUrl=/Gradle template: /p'
  else
    echo "Gradle template version is available after npm ci; Gradle prints its version at build startup."
  fi
  for properties in "$ANDROID_HOME"/build-tools/*/source.properties "$ANDROID_HOME"/platforms/*/source.properties; do
    [ ! -f "$properties" ] || sed -n 's/^Pkg.Desc=//p; s/^Pkg.Revision=/Android SDK revision: /p' "$properties"
  done
fi
if [ -z "$work" ]; then
  [ -z "$(git status --porcelain)" ] || echo "Checking commit $(git rev-parse --short HEAD); uncommitted changes are not part of this check." >&2
  work="$(mktemp -d)/power-log"
  git worktree add --quiet --detach "$work" HEAD
  finish() {
    status=$?
    cd "$root"
    if [ "$status" -eq 0 ]; then
      git worktree remove --force "$work"
    else
      echo "Checks failed; the checkout is kept at $work (remove it with: git worktree remove --force $work)." >&2
    fi
    exit "$status"
  }
  trap finish EXIT
fi
cd "$work"
node scripts/checks.mjs $tier
if [ "$tier" = --full ]; then
  echo "Every check passed for $(git rev-parse --short HEAD)."
fi
