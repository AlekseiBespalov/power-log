#!/bin/sh
set -eu
power_log_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$power_log_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
unset POWER_LOG_LAB
power_log_prebuild=--no-clean
if [ "$(cat ios/.power-log-flavor 2>/dev/null)" != production ]; then
  power_log_prebuild=--clean
fi
node scripts/prebuild.mjs --platform ios --no-install "$power_log_prebuild"
echo production > ios/.power-log-flavor
node <<'JS'
const fs = require('node:fs');
const xcode = require('xcode');
const project = xcode.project('ios/PowerLog.xcodeproj/project.pbxproj');
project.parseSync();
const [targetId] = Object.entries(project.pbxNativeTargetSection()).find(
  ([, target]) => target?.isa === 'PBXNativeTarget' && String(target.name).replaceAll('"', '') === 'PowerLogWatch',
) ?? [];
if (!targetId) throw new Error('Watch Release target is missing.');
const directory = 'ios/PowerLog.xcodeproj/xcshareddata/xcschemes';
fs.mkdirSync(directory, { recursive: true });
fs.writeFileSync(`${directory}/PowerLogWatchReleaseLink.xcscheme`, `<?xml version="1.0" encoding="UTF-8"?>
<Scheme version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="NO">
    <BuildActionEntries>
      <BuildActionEntry buildForTesting="NO" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">
        <BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="${targetId}" BuildableName="PowerLogWatch.app" BlueprintName="PowerLogWatch" ReferencedContainer="container:PowerLog.xcodeproj"/>
      </BuildActionEntry>
    </BuildActionEntries>
  </BuildAction>
</Scheme>
`);
JS
power_log_watch_build="$power_log_root/shared/build/watch-release"
mkdir -p "$power_log_root/shared/build/xcode-modules"
touch "$power_log_root/shared/build/xcode-modules/Session.modulevalidation"
xcodebuild -project ios/PowerLog.xcodeproj -scheme PowerLogWatchReleaseLink -configuration Release \
  -derivedDataPath "$power_log_root/shared/build/watch-derived" \
  -destination 'generic/platform=watchOS' -sdk watchos ARCHS='arm64_32 arm64' \
  CODE_SIGNING_ALLOWED=NO CONFIGURATION_BUILD_DIR="$power_log_watch_build" \
  MODULE_CACHE_DIR="$power_log_root/shared/build/xcode-modules" \
  CLANG_MODULE_CACHE_PATH="$power_log_root/shared/build/xcode-modules" \
  CLANG_MODULES_BUILD_SESSION_FILE="$power_log_root/shared/build/xcode-modules/Session.modulevalidation" \
  SHARED_PRECOMPS_DIR="$power_log_root/shared/build/xcode-modules" build
power_log_watch_binary="$power_log_watch_build/PowerLogWatch.app/PowerLogWatch"
for power_log_watch_arch in arm64_32 arm64; do
  lipo "$power_log_watch_binary" -verify_arch "$power_log_watch_arch"
done
lipo -info "$power_log_watch_binary"
wc -c "$power_log_watch_binary"
mkdir -p "$power_log_root/shared/build/qualification"
touch "$power_log_root/shared/build/qualification/watch-links.done"
