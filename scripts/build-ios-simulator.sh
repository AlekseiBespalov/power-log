#!/bin/sh
set -eu
unset POWER_LOG_STRESS_ARGUMENT
power_log_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$power_log_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export LANG="${LANG:-en_US.UTF-8}"
power_log_flavor=production
if [ "${POWER_LOG_LAB:-0}" = 1 ]; then
  power_log_flavor=lab
  node scripts/prepare-apple-crash-tests.mjs
fi
power_log_prebuild=--no-clean
if [ "$(cat ios/.power-log-flavor 2>/dev/null)" != "$power_log_flavor" ]; then
  power_log_prebuild=--clean
fi
printf "Apple prebuild: %s flavor, %s\n" "$power_log_flavor" "$power_log_prebuild"
node scripts/prebuild.mjs --platform ios --no-install "$power_log_prebuild"
echo "$power_log_flavor" > ios/.power-log-flavor
sh scripts/install-pods.sh
set -- CODE_SIGNING_ALLOWED=NO
power_log_configuration=Release
if [ "${POWER_LOG_LAB:-0}" = 1 ]; then
  power_log_configuration=Debug
  set -- CODE_SIGNING_ALLOWED=YES CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=-
fi
xcodebuild -jobs 8 -workspace ios/PowerLog.xcworkspace -scheme PowerLog -configuration "$power_log_configuration" -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath ios/build build "$@" \
  ARCHS="$(uname -m)"
if [ "${POWER_LOG_LAB:-0}" = 1 ]; then
  power_log_lab_app="$power_log_root/ios/build/Build/Products/Debug-iphonesimulator/PowerLog.app"
  npm run test:ios-bundle -- "$power_log_lab_app"
  codesign --verify --deep --strict "$power_log_lab_app"
else
  node scripts/check-apple-release-surface.mjs "$power_log_root/ios/build/Build/Products/Release-iphonesimulator/PowerLog.app"
fi
echo "Linked simulator build of the iPhone app and its Watch app passed."
