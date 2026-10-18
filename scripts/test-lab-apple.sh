#!/bin/sh
set -eu
power_log_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$power_log_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"
export PATH="/opt/homebrew/opt/node@24/bin:$PATH"
bash scripts/test-swift.sh --lab
node --input-type=module -e 'import {checkSources} from "./scripts/check-apple-release-surface.mjs"; checkSources()'
if [ "${1:-}" = "--simulators" ]; then
  shift
  node scripts/test-apple-crash-paths.mjs "$@"
fi
