#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
power_log_bundle="${POWER_LOG_DEVICE_BUNDLE:-app.powerlog}"
case "${1:-}" in
  install)
    npm run test:ios-bundle -- "$POWER_LOG_DEVICE_APP"
    codesign --verify --deep --strict "$POWER_LOG_DEVICE_APP"
    xcrun devicectl device install app --device "$POWER_LOG_DEVICE_ID" "$POWER_LOG_DEVICE_APP"
    ;;
  push)
    xcrun devicectl device copy to --device "$POWER_LOG_DEVICE_ID" \
      --source "$PWD/test-results/device-eight-hour/cold/PowerLog" \
      --destination 'Library/Application Support/PowerLog' \
      --domain-type appDataContainer --domain-identifier "$power_log_bundle" --remove-existing-content true
    ;;
  pull)
    xcrun devicectl device copy from --device "$POWER_LOG_DEVICE_ID" \
      --source 'Library/Application Support/PowerLog' \
      --destination "$PWD/test-results/device-eight-hour/copied/PowerLog" \
      --domain-type appDataContainer --domain-identifier "$power_log_bundle"
    ;;
  *)
    echo 'Usage: sh scripts/device-eight-hour/device.sh install|push|pull' >&2
    exit 2
    ;;
esac
