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
      set --
      [ -z "${POWER_LOG_PRETTIER_CACHE:-}" ] || set -- --cache --cache-location "$POWER_LOG_PRETTIER_CACHE" --cache-strategy content
      npx prettier "$mode" "$@" "src/**/*.{ts,tsx}" "tests/**/*.{ts,tsx}" "modules/power-log/*.ts" "modules/power-log/src/**/*.{ts,tsx}" "plugins/*.js" "scripts/*.mjs" "shared/scripts/*.mjs" "shared/generated/js/*/package.json" "*.ts" "*.js" || status=1
      ;;
    swift)
      if [ "$mode" = --write ]; then swift_args="format --in-place"; else swift_args="lint --strict"; fi
      xcrun swift-format $swift_args --configuration .swift-format --recursive modules/power-log/ios targets/watch targets/live-activity artifacts/app-store/source scripts || status=1
      ;;
    kotlin)
      jar=$(ktfmt_jar) || { echo "Could not download ktfmt $ktfmt_version" >&2; status=1; continue; }
      if [ "$mode" = --write ]; then ktfmt_args=""; else ktfmt_args="--dry-run --set-exit-if-changed"; fi
      find modules/power-log/android/src shared \( -type d \( -name build -o -name .gradle -o -name .kotlin \) -o -path shared/generated \) -prune -o -type f \( -name '*.kt' -o -name '*.kts' \) -print0 | xargs -0 java -jar "$jar" --kotlinlang-style --enable-editorconfig $ktfmt_args || status=1
      ;;
  esac
done
exit $status
