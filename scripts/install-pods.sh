#!/bin/sh
set -eu
cd "$(dirname "$0")/../ios"
bundle exec pod install
