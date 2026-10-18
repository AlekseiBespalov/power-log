#!/bin/sh
set -eu
power_log_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$power_log_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
if [ "$(uname -s)" != Darwin ]; then
  echo "Swift native checks require macOS with Xcode." >&2
  exit 1
fi
exec node scripts/swift-suites.mjs "$@"
