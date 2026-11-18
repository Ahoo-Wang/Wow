#!/usr/bin/env bash
# The #6379 confirming run's two arms, unmodified: base = the fix's merge base with main, fix = the fix branch's head.
set -eu
S=/private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-rt-subs-p1-2727ce/4a806155-973d-405b-99f7-d65b7252944a/scratchpad
REPO=/Users/ahoowang/work/ahoo/Wow/.claude/worktrees/rt-subs-p1-2727ce
FIX=$S/wt-lost
git -C $REPO fetch -q origin main
BASE_SHA=$(git -C $FIX merge-base HEAD origin/main)
T=$S/lostx/tree-base
[ -d $T ] || git -C $REPO worktree add -q --detach $T $BASE_SHA
git -C $T checkout -q --detach $BASE_SHA
[ -z "$(git -C $FIX status --porcelain)" ] || { echo "the fix tree has uncommitted changes"; exit 1; }
echo "base $(git -C $T rev-parse HEAD)"; echo "fix  $(git -C $FIX rev-parse HEAD)"
for t in $T $FIX; do (cd $t && ./gradlew --console=plain -q :benchmark-server:installDist :benchmark-load:installDist); done
