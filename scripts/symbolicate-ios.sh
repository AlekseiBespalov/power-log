#!/bin/sh
set -eu
if [ "$#" -ne 2 ]; then
  echo "Usage: symbolicate-ios.sh <diagnostics.zip | connection-log.jsonl> <releases directory>" >&2
  exit 1
fi
exec node "$(dirname -- "$0")/symbolicate-ios.mjs" "$1" "$2"
