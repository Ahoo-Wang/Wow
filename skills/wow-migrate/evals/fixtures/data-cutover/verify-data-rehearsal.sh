#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
root="${1:-.}"
phase="${2:-complete}"

exec python3 "$script_dir/verify-data-rehearsal.py" "$root" "$phase"
