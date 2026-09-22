#!/bin/sh
set -u
cd "$(dirname "$0")/.."
mode=${1:---write}
case "$mode" in
  --write) swift_args="format --in-place" ;;
  --check) swift_args="lint --strict" ;;
  *) echo "Usage: sh scripts/format.sh [--write|--check]" >&2; exit 1 ;;
esac
status=0
npx prettier "$mode" "src/**/*.{ts,tsx}" "tests/**/*.{ts,tsx}" "modules/cyc-bridge/*.ts" "plugins/*.js" "scripts/*.mjs" "*.ts" "*.js" || status=1
xcrun swift-format $swift_args --configuration .swift-format --recursive modules/cyc-bridge/ios apple/WatchApp apple/LiveActivity artifacts/app-store/source tests/workout-native tests/catalog-native scripts || status=1
exit $status
