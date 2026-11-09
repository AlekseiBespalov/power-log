#!/bin/sh
set -eu
power_log_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$power_log_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export LANG="${LANG:-en_US.UTF-8}"
power_log_flavor=production
case "${1:-}" in
  "") ;;
  --development)
    power_log_flavor=development
    export POWER_LOG_DEV_CLIENT=1 POWER_LOG_APPLE_SLICE=device
    ;;
  *)
    echo "Usage: build-ios-device.sh [--development]" >&2
    exit 1
    ;;
esac
power_log_prebuild=--no-clean
if [ "$(cat ios/.power-log-flavor 2>/dev/null)" != "$power_log_flavor" ]; then
  power_log_prebuild=--clean
fi
printf "Apple prebuild: %s flavor, %s\n" "$power_log_flavor" "$power_log_prebuild"
node scripts/prebuild.mjs --platform ios --no-install "$power_log_prebuild"
echo "$power_log_flavor" > ios/.power-log-flavor
sh scripts/install-pods.sh
xcodebuild -jobs 8 -workspace ios/PowerLog.xcworkspace -scheme PowerLog -configuration Release -sdk iphoneos \
  -destination 'generic/platform=iOS' -derivedDataPath ios/build -allowProvisioningUpdates build \
  DEBUG_INFORMATION_FORMAT=dwarf-with-dsym
power_log_products="$power_log_root/ios/build/Build/Products"
power_log_app="$power_log_products/Release-iphoneos/PowerLog.app"
if [ "$power_log_flavor" = production ]; then
  node scripts/check-apple-release-surface.mjs "$power_log_app"
fi
power_log_version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$power_log_app/Info.plist")
power_log_build=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$power_log_app/Info.plist")
power_log_commit=$(git rev-parse --short HEAD)
if [ -n "$(git status --porcelain)" ]; then
  power_log_commit="$power_log_commit-dirty"
fi
power_log_symbols="$HOME/.cache/power-log/releases/$power_log_version-$power_log_build-$power_log_commit-$power_log_flavor/ios/dSYMs"
rm -rf "$power_log_symbols"
mkdir -p "$power_log_symbols"
find "$power_log_products" -maxdepth 2 -name '*.dSYM' -exec cp -R {} "$power_log_symbols/" \;
for power_log_dsym in "$power_log_symbols"/*.dSYM; do
  xcrun dwarfdump --uuid "$power_log_dsym"
done > "$power_log_symbols/uuids.txt"
echo "Device build of the iPhone app and its Watch app; dSYMs in $power_log_symbols"
