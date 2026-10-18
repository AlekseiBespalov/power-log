#!/bin/sh
set -eu
cd "$(dirname "$0")/../../.."
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
sh shared/scripts/build-apple.sh --macos
exec node artifacts/app-store/source/generate.mjs "$@"
