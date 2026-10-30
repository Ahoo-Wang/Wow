#!/usr/bin/env bash
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

run_jdk25() {
  local session_dir="$OUT_ROOT/6-J25-postgres"
  mkdir -p "$session_dir"
  say "=== Section 6: Starting JDK 25 Runtime on PostgreSQL (Session 6-J25-postgres) ==="

  local orig_java_home="$JAVA_HOME"
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
  say "Switched JAVA_HOME to JDK 25: $("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"

  if ! safe_up postgres "1g"; then
    say "[JDK 25 postgres] up failed"
    export JAVA_HOME="$orig_java_home"
    return 1
  fi

  local C="--backend=postgres --accounts=$ACCOUNTS --heap=1g"

  say "[JDK 25 postgres] Running E1 uniform sweep (to 4096)..."
  local p_e1_sweep="$session_dir/e1-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --scenario=E1 --distribution=uniform $SWEEP_ARGS --out="$p_e1_sweep"; then
    write_sweep_summary "$p_e1_sweep"
  else
    say "[JDK 25 postgres] E1 sweep failed"
  fi

  say "[JDK 25 postgres] Running E3 uniform sweep (to 4096)..."
  local p_e3_sweep="$session_dir/e3-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --scenario=E3 --distribution=uniform $SWEEP_ARGS --out="$p_e3_sweep"; then
    write_sweep_summary "$p_e3_sweep"
  else
    say "[JDK 25 postgres] E3 sweep failed"
  fi

  safe_down
  export JAVA_HOME="$orig_java_home"
  say "=== Section 6: JDK 25 Runtime Complete, restored JAVA_HOME ==="
}

say "Starting Section 6: JDK 25 Runtime (6-J25-postgres)"
run_jdk25
say "Section 6 Finished"
