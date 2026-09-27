#!/bin/sh
set -eu
power_log_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$power_log_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export LANG="${LANG:-en_US.UTF-8}"
node node_modules/expo/bin/cli prebuild --platform ios --no-install
(cd ios && bundle exec pod install)
xcodebuild -workspace ios/PowerLog.xcworkspace -scheme PowerLog -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath ios/build build CODE_SIGNING_ALLOWED=NO \
  ARCHS="$(uname -m)"
echo "Linked simulator build of the iPhone app and its Watch app passed (deployment target from app.config.ts)."
