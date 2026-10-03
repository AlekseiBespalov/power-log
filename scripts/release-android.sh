#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
version=${1:-}
tag="v$version"
fail() {
  echo "$1" >&2
  exit 1
}
echo "$version" | grep -Eq '^[0-9]+\.[0-9]{1,2}\.[0-9]{1,2}$' || fail "Usage: sh scripts/release-android.sh <major.minor.patch>"
[ -z "$(git status --porcelain)" ] || fail "Commit or remove local changes first; the release is built from the commit alone."
[ "$(node -p "require('./package.json').version")" = "$version" ] || fail "package.json is not at version $version."
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || fail "Release from main."
git fetch --quiet origin main
commit=$(git rev-parse HEAD)
[ "$commit" = "$(git rev-parse origin/main)" ] || fail "Push main and pull its latest commit first."
[ -z "$(git ls-remote --tags origin "refs/tags/$tag")" ] || fail "$tag already exists on GitHub."
for key in POWER_LOG_ANDROID_KEYSTORE POWER_LOG_ANDROID_STORE_PASSWORD POWER_LOG_ANDROID_KEY_ALIAS POWER_LOG_ANDROID_KEY_PASSWORD; do
  eval "value=\${$key:-}"
  [ -n "$value" ] || fail "Set $key for the release signing key."
done
[ -f "$POWER_LOG_ANDROID_KEYSTORE" ] || fail "POWER_LOG_ANDROID_KEYSTORE does not name a file."
previous=$(gh release view --json tagName --jq .tagName) || fail "Could not read the latest GitHub release."
[ -n "$previous" ] || fail "Could not read the latest GitHub release."

work="$(mktemp -d)/power-log"
git worktree add --quiet --detach "$work" "$commit"
finish() {
  status=$?
  cd "$root"
  if [ "$status" -eq 0 ]; then
    git worktree remove --force "$work"
  else
    echo "Release stopped; the checkout is kept at $work (remove it with: git worktree remove --force $work)." >&2
  fi
}
trap finish EXIT

sh scripts/verify.sh "$work"

cd "$work"
code=$(echo "$version" | awk -F. '{ print $1 * 10000 + $2 * 100 + $3 }')
POWER_LOG_ANDROID_VERSION_CODE=$code npm run build:android
apk="$work/artifacts/builds/android/power-log.apk"

sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
tools=$(ls "$sdk/build-tools" | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)
certificate() {
  out=$("$sdk/build-tools/$tools/apksigner" verify --print-certs "$1") || fail "apksigner could not verify $1."
  digest=$(printf '%s\n' "$out" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
  echo "$digest" | grep -Eq '^[0-9a-f]{64}$' || fail "No signing certificate digest in $1."
  printf '%s' "$digest"
}
gh release download "$previous" --pattern power-log.apk --dir "$work/previous"
new=$(certificate "$apk")
old=$(certificate "$work/previous/power-log.apk")
[ "$new" = "$old" ] || fail "The new APK is not signed with the key of $previous, so installed copies could not update. Nothing was published."

(cd artifacts/builds/android && shasum -a 256 power-log.apk > SHA256SUMS.txt)
git -C "$root" tag -a "$tag" -m "Power Log $version" "$commit"
git -C "$root" push origin "$tag"
gh release create "$tag" --verify-tag --generate-notes "$apk" artifacts/builds/android/SHA256SUMS.txt
