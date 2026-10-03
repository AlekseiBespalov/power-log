#!/bin/sh
# Usage: sh scripts/verify.sh [checkout]
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
if [ $# -gt 0 ]; then
  work=$1
else
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
  }
  trap finish EXIT
fi
cd "$work"
export CI=1
export FIT_PYTHON="${FIT_PYTHON:-$HOME/.cache/power-log/fit-venv/bin/python}"
if [ -z "${BUNDLE_PATH:-}" ] && [ -d "$HOME/.cache/power-log/bundle" ]; then export BUNDLE_PATH="$HOME/.cache/power-log/bundle"; fi
npm ci
npm audit --audit-level=moderate
npm run check
npm run test:browser
npm run test:e2e
npm run test:public-web
sh scripts/format.sh --swift --kotlin --check
bash scripts/test-swift.sh
npm run build:ios-simulator
npm run build:android -- --preview --arch=arm64-v8a
echo "Every check passed for $(git rev-parse --short HEAD)."
