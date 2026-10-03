#!/bin/sh
set -u
cd "$(dirname "$0")/.."
ktfmt_version=0.64
usage() { echo "Usage: sh scripts/format.sh [--ts] [--swift] [--kotlin] [--write|--check]" >&2; exit 1; }
mode=--write
languages=""
for arg in "$@"; do
  case "$arg" in
    --write | --check) mode=$arg ;;
    --ts | --swift | --kotlin) languages="$languages ${arg#--}" ;;
    *) usage ;;
  esac
done
[ -n "$languages" ] || languages="ts swift kotlin"
ktfmt_jar() {
  jar="${XDG_CACHE_HOME:-$HOME/.cache}/power-log/ktfmt-$ktfmt_version.jar"
  if [ ! -f "$jar" ]; then
    mkdir -p "$(dirname "$jar")"
    curl -fsSL -o "$jar.part" "https://repo1.maven.org/maven2/com/facebook/ktfmt/$ktfmt_version/ktfmt-$ktfmt_version-with-dependencies.jar" && mv "$jar.part" "$jar" || return 1
  fi
  echo "$jar"
}
status=0
for language in $languages; do
  case "$language" in
    ts)
      npx prettier "$mode" "src/**/*.{ts,tsx}" "tests/**/*.{ts,tsx}" "modules/cyc-bridge/*.ts" "plugins/*.js" "scripts/*.mjs" "*.ts" "*.js" || status=1
      ;;
    swift)
      if [ "$mode" = --write ]; then swift_args="format --in-place"; else swift_args="lint --strict"; fi
      xcrun swift-format $swift_args --configuration .swift-format --recursive modules/cyc-bridge/ios apple/WatchApp apple/LiveActivity artifacts/app-store/source tests/catalog-native scripts || status=1
      ;;
    kotlin)
      jar=$(ktfmt_jar) || { echo "Could not download ktfmt $ktfmt_version" >&2; status=1; continue; }
      if [ "$mode" = --write ]; then ktfmt_args=""; else ktfmt_args="--dry-run --set-exit-if-changed"; fi
      find modules/cyc-bridge/android/src -name '*.kt' -print0 | xargs -0 java -jar "$jar" --kotlinlang-style --enable-editorconfig $ktfmt_args || status=1
      ;;
  esac
done
exit $status
