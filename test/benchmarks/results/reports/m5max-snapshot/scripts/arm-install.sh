#!/usr/bin/env bash
#
# Another commit's server, as an arm of a two-arm run (run-all.sh): <ref> built in a worktree of its own, outside the
# checkout and removed after, its installed distribution copied to test/benchmarks/results/e2e/installs/<ref>/ (ignored
# by git) inside the checkout, where lib.sh tells its server for this benchmark's. Prints the install's path, for
# SERVER_INSTALL. A build older than the build stamp (#6256) is given one: the commit it was built from, its tree
# clean, so every figure of the arm names its build. Run it before the batch lock is taken: it runs Gradle.
#
#   arm-install.sh <ref>     (WOW_REPO as for 01-single-node.sh)
set -euo pipefail

ref=${1:?usage: arm-install.sh <ref>}
here="$(cd "$(dirname "$0")" && pwd)"
REPO="${WOW_REPO:-$(git -C "$here" rev-parse --show-toplevel)}"
commit=$(git -C "$REPO" rev-parse --verify "$ref^{commit}")
target="$REPO/test/benchmarks/results/e2e/installs/$ref"
tree=$(mktemp -d "${TMPDIR:-/tmp}/wow-arm.XXXXXX")
trap 'git -C "$REPO" worktree remove --force "$tree" >/dev/null 2>&1 || rm -rf "$tree"' EXIT

git -C "$REPO" worktree add --detach "$tree" "$commit" >&2
(cd "$tree" && ./gradlew --console=plain -q :benchmark-server:installDist) >&2
rm -rf "$target"
mkdir -p "$target"
cp -R "$tree/test/benchmarks/server/build/install/benchmark-server" "$target/"
install="$target/benchmark-server"
if [ ! -f "$install/wow-build-stamp" ]; then printf 'commit=%s\ndirty=no\n' "$commit" >"$install/wow-build-stamp"; fi
echo "$install"
