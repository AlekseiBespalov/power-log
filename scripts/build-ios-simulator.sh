#!/bin/sh
set -eu
unset POWER_LOG_STRESS_ARGUMENT
power_log_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$power_log_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export LANG="${LANG:-en_US.UTF-8}"
power_log_flavor=production
power_log_prebuild=--no-clean
if [ "$(cat ios/.power-log-flavor 2>/dev/null)" != "$power_log_flavor" ]; then
  power_log_prebuild=--clean
fi
printf "Apple prebuild: %s flavor, %s\n" "$power_log_flavor" "$power_log_prebuild"
node scripts/prebuild.mjs --platform ios --no-install "$power_log_prebuild"
echo "$power_log_flavor" > ios/.power-log-flavor
sh scripts/install-pods.sh
xcodebuild -jobs 8 -workspace ios/PowerLog.xcworkspace -scheme PowerLog -configuration Release -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath ios/build build CODE_SIGNING_ALLOWED=NO \
  ARCHS="$(uname -m)"
node scripts/check-apple-release-surface.mjs "$power_log_root/ios/build/Build/Products/Release-iphonesimulator/PowerLog.app"
echo "Linked simulator build of the iPhone app and its Watch app passed."
